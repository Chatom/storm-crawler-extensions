package com.shopstyle.crawler.jsoup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import backtype.storm.metric.api.MultiCountMetric;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

import com.digitalpebble.storm.crawler.Constants;
import com.digitalpebble.storm.crawler.Metadata;
import com.digitalpebble.storm.crawler.filtering.URLFilters;
import com.digitalpebble.storm.crawler.parse.ParseFilter;
import com.digitalpebble.storm.crawler.parse.ParseFilters;
import com.digitalpebble.storm.crawler.persistence.Status;
import com.digitalpebble.storm.crawler.protocol.HttpHeaders;
import com.digitalpebble.storm.crawler.util.ConfUtils;
import com.digitalpebble.storm.crawler.util.MetadataTransfer;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.DocumentFragment;

import static com.digitalpebble.storm.crawler.Constants.StatusStreamName;

/**
 * Simple parser for HTML documents which calls ParseFilters to add metadata. Does not handle
 * outlinks for now.
 */
@SuppressWarnings("serial")
public class JSoupParserBolt extends BaseRichBolt {

    /** Metadata key name for tracking the anchors */
    public static final String ANCHORS_KEY_NAME = "anchors";

    private static final Logger LOG = LoggerFactory.getLogger(JSoupParserBolt.class);

    private OutputCollector collector;

    private MultiCountMetric eventCounter;

    private ParseFilter parseFilters = null;

    private URLFilters urlFilters = null;

    private MetadataTransfer metadataTransfer;

    private boolean trackAnchors = true;

    private boolean emitOutlinks = true;

    @Override
    public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;

        eventCounter =
                context.registerMetric(this.getClass().getSimpleName(), new MultiCountMetric(), 10);

        parseFilters = ParseFilters.emptyParseFilter;

        String parseconfigfile =
                ConfUtils.getString(conf, "parsefilters.config.file", "parsefilters.json");
        if (parseconfigfile != null) {
            try {
                parseFilters = new ParseFilters(conf, parseconfigfile);
            } catch (IOException e) {
                LOG.error("Exception caught while loading the ParseFilters");
                throw new RuntimeException("Exception caught while loading the ParseFilters", e);
            }
        }

        urlFilters = URLFilters.emptyURLFilters;
        emitOutlinks = ConfUtils.getBoolean(conf, "parser.emitOutlinks", true);

        if (emitOutlinks) {
            String urlconfigfile =
                    ConfUtils.getString(conf, "urlfilters.config.file", "urlfilters.json");

            if (urlconfigfile != null) {
                try {
                    urlFilters = new URLFilters(conf, urlconfigfile);
                } catch (IOException e) {
                    LOG.error("Exception caught while loading the URLFilters");
                    throw new RuntimeException("Exception caught while loading the URLFilters", e);
                }
            }
        }

        trackAnchors = ConfUtils.getBoolean(conf, "track.anchors", true);

        metadataTransfer = MetadataTransfer.getInstance(conf);
    }

    @Override
    public void execute(Tuple tuple) {

        byte[] content = tuple.getBinaryByField("content");
        String url = tuple.getStringByField("url");
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");

        long start = System.currentTimeMillis();

        String charset = getContentCharset(content, metadata);

        Map<String, List<String>> slinks;
        String text;
        DocumentFragment fragment;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(content)) {
            org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(bais, charset, url);
            fragment = DOMBuilder.jsoup2HTML(jsoupDoc);

            Elements links = jsoupDoc.select("a[href]");
            slinks = new HashMap<String, List<String>>(links.size());
            for (Element link : links) {
                // abs:href tells jsoup to return fully qualified domains for relative urls.
                // e.g.: /foo will resolve to http://shopstyle.com/foo
                String targetURL = link.attr("abs:href");
                String anchor = link.text();
                if (StringUtils.isNotBlank(targetURL)) {
                    List<String> anchors = slinks.get(targetURL);
                    if (anchors == null) {
                        anchors = new LinkedList<String>();
                        slinks.put(targetURL, anchors);
                    }
                    if (StringUtils.isNotBlank(anchor)) {
                        anchors.add(anchor);
                    }
                }
            }

            text = jsoupDoc.body().text();

        } catch (Throwable e) {
            String errorMessage = "Exception while parsing " + url + ": " + e;
            LOG.error(errorMessage);
            // send to status stream in case another component wants to update
            // its status
            metadata.setValue(Constants.STATUS_ERROR_SOURCE, "content parsing");
            metadata.setValue(Constants.STATUS_ERROR_MESSAGE, errorMessage);
            collector.emit(StatusStreamName, tuple, new Values(url, metadata, Status.ERROR));
            collector.ack(tuple);
            // Increment metric that is context specific
            eventCounter.scope("error_content_parsing_" + e.getClass().getSimpleName()).incrBy(1);
            // Increment general metric
            eventCounter.scope("parse exception").incrBy(1);
            return;
        }

        long duration = System.currentTimeMillis() - start;

        LOG.info("Parsed {} in {} msec", url, duration);

        // apply the parse filters if any
        try {
            parseFilters.filter(url, content, fragment, metadata);
        } catch (RuntimeException e) {
            String errorMessage = "Exception while running parse filters on " + url + ": " + e;
            LOG.error(errorMessage);
            metadata.setValue(Constants.STATUS_ERROR_SOURCE, "content filtering");
            metadata.setValue(Constants.STATUS_ERROR_MESSAGE, errorMessage);
            collector.emit(StatusStreamName, tuple, new Values(url, metadata, Status.ERROR));
            collector.ack(tuple);
            // Increment metric that is context specific
            eventCounter.scope("error_content_filtering_" + e.getClass().getSimpleName()).incrBy(1);
            // Increment general metric
            eventCounter.scope("parse exception").incrBy(1);
            return;
        }

        if (emitOutlinks && !slinks.isEmpty()) {
            emitOutlinks(tuple, url, metadata, slinks);
        }

        collector.emit(tuple, new Values(url, content, metadata, text.trim()));
        collector.ack(tuple);
        eventCounter.scope("tuple_success").incr();
    }


    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // output of this module is the list of fields to index
        // with at least the URL, text content
        declarer.declare(new Fields("url", "content", "metadata", "text"));
        declarer.declareStream(StatusStreamName, new Fields("url", "metadata", "status"));
    }

    private String getContentCharset(byte[] content, Metadata metadata) {
        String charset = null;

        // check if the server specified a charset
        String specifiedContentType = metadata.getFirstValue(HttpHeaders.CONTENT_TYPE);
        try {
            if (specifiedContentType != null) {
                ContentType parsedContentType = ContentType.parse(specifiedContentType);
                charset = parsedContentType.getCharset().name();
            }
        } catch (Exception e) {
            charset = null;
        }

        // filter HTML tags
        CharsetDetector detector = new CharsetDetector();
        detector.enableInputFilter(true);
        // give it a hint
        detector.setDeclaredEncoding(charset);
        detector.setText(content);
        try {
            CharsetMatch charsetMatch = detector.detect();
            if (charsetMatch != null) {
                charset = charsetMatch.getName();
            }
        } catch (Exception e) {
            // ignore and leave the charset as-is
        }
        return charset;
    }

    private void emitOutlinks(Tuple tuple, String url, Metadata metadata, Map<String, List<String>> slinks) {
        // get the outlinks and convert them to strings (for now)
        URL sourceUrl;
        try {
            sourceUrl = new URL(url);
        } catch (MalformedURLException e) {
            // we would have known by now as previous components check whether the URL is valid
            LOG.error("MalformedURLException on {}", url);
            eventCounter.scope("error_invalid_source_url").incrBy(1);
            return;
        }

        Map<String, List<String>> linksKept = new HashMap<String, List<String>>();

        for (Map.Entry<String, List<String>> linkEntry : slinks.entrySet()) {
            String targetURL = linkEntry.getKey();
            // filter the urls
            if (urlFilters != null) {
                targetURL = urlFilters.filter(sourceUrl, metadata, targetURL);
                if (targetURL == null) {
                    eventCounter.scope("outlink_filtered").incr();
                    continue;
                }
            }
            // the link has survived the various filters
            if (targetURL != null) {
                List<String> anchors = linkEntry.getValue();
                linksKept.put(targetURL, anchors);
                eventCounter.scope("outlink_kept").incr();
            }
        }

        for (String outlink : linksKept.keySet()) {
            // configure which metadata gets inherited from parent
            Metadata linkMetadata = metadataTransfer.getMetaForOutlink(outlink, url, metadata);
            // add the anchors to the metadata?
            if (trackAnchors) {
                List<String> anchors = linksKept.get(outlink);
                if (anchors.size() > 0) {
                    linkMetadata.addValues(ANCHORS_KEY_NAME, anchors);
                }
            }
            collector.emit(StatusStreamName, tuple, new Values(outlink, linkMetadata,
                    Status.DISCOVERED));
        }
    }
}
