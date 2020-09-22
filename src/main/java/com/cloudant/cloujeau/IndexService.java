package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.asAtom;
import static com.cloudant.cloujeau.OtpUtils.asBinary;
import static com.cloudant.cloujeau.OtpUtils.asBoolean;
import static com.cloudant.cloujeau.OtpUtils.asInt;
import static com.cloudant.cloujeau.OtpUtils.asLong;
import static com.cloudant.cloujeau.OtpUtils.asMap;
import static com.cloudant.cloujeau.OtpUtils.asString;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.IOUtils;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau");

    private final String name;

    private final IndexWriter writer;

    private final SearcherManager searcherManager;

    private final QueryParser qp;

    private long updateSeq;

    private long pendingSeq;

    private long purgeSeq;

    private long pendingPurgeSeq;

    private boolean committing = false;

    private boolean forceRefresh = false;

    private boolean idle = false;

    public IndexService(final ServerState state, final String name, final IndexWriter writer, final QueryParser qp)
            throws ReflectiveOperationException, IOException {
        super(state);
        if (state == null) {
            throw new NullPointerException("state cannot be null");
        }
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        if (writer == null) {
            throw new NullPointerException("writer cannot be null");
        }
        if (qp == null) {
            throw new NullPointerException("qp cannot be null");
        }
        this.name = name;
        this.writer = writer;
        this.searcherManager = new SearcherManager(writer, true, null);
        this.qp = qp;
        this.updateSeq = getCommittedSeq();
        this.pendingSeq = updateSeq;
        this.purgeSeq = getCommittedPurgeSeq();
        this.pendingPurgeSeq = purgeSeq;
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws IOException {
        if (request instanceof OtpErlangAtom) {
            switch (asString(request)) {
            case "get_update_seq":
                return tuple(asAtom("ok"), asLong(updateSeq));
            case "get_purge_seq":
                return tuple(asAtom("ok"), asLong(purgeSeq));
            }
        } else if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            final OtpErlangObject cmd = tuple.elementAt(0);

            if (cmd instanceof OtpErlangAtom) {
                switch (asString(cmd)) {
                case "commit": // deprecated
                case "set_update_seq": {
                    pendingSeq = asLong(tuple.elementAt(1));
                    debug("Pending sequence is now " + pendingSeq);
                    return asAtom("ok");
                }
                case "set_purge_seq": {
                    pendingPurgeSeq = asLong(tuple.elementAt(1));
                    debug("purge sequence is now " + pendingPurgeSeq);
                    return asAtom("ok");
                }
                case "delete": {
                    final String id = asString(tuple.elementAt(1));
                    debug(String.format("Deleting %s", id));
                    writer.deleteDocuments(new Term("_id", id));
                    return asAtom("ok");
                }
                case "update": {
                    final Document doc = ClouseauTypeFactory.newDocument(tuple.elementAt(1), tuple.elementAt(2));
                    debug("Updating " + doc.get("_id"));
                    writer.updateDocument(new Term("_id", doc.get("_id")), doc);
                    return asAtom("ok");
                }
                case "search":
                    return handleSearchCall(from, asMap(tuple.elementAt(1)));
                }
            }
        }

        return null;
    }

    @Override
    public void terminate(final OtpErlangObject reason) {
        IOUtils.closeWhileHandlingException(searcherManager, writer);
    }

    private OtpErlangObject handleSearchCall(final OtpErlangTuple from, final Map<OtpErlangObject,OtpErlangObject> searchRequest) {
        final String queryString = asString(searchRequest.getOrDefault(asAtom("query"), asBinary("*:*")));
        final boolean refresh = asBoolean(searchRequest.getOrDefault(asAtom("refresh"), asAtom("true")));
        final int limit = asInt(searchRequest.getOrDefault(asAtom("limit"), asInt(25)));
        final String partition = asString(searchRequest.get(asAtom("partition")));
        
        final Query baseQuery = parseQuery(queryString, partition);

        System.err.println(queryString);
        return null;
    }

    private Query parseQuery(final String query, final String partition) throws ParseException {
        if (partition == null) {
            return qp.parse(query);
        } else {
            final BooleanQuery result = new BooleanQuery();
            result.add(new TermQuery(new Term("_partition", partition)), Occur.MUST);
            result.add(qp.parse(query), Occur.MUST);
            return result;
        }
    }

    private long getCommittedSeq() {
        return getLong("update_seq");
    }

    private long getCommittedPurgeSeq() {
        return getLong("purge_seq");
    }

    private long getLong(final String name) {
        final String val = writer.getCommitData().get(name);
        if (val == null) {
            return 0;
        }
        return Long.parseLong(val);
    }

    private void debug(final String str) {
        logger.debug(prefix_name(str));
    }

    private void info(final String str) {
        logger.info(prefix_name(str));
    }

    private void error(final String str) {
        logger.error(prefix_name(str));
    }

    private void warn(final String str, final Throwable t) {
        logger.warn(prefix_name(str), t);
    }

    private void error(final String str, final Throwable t) {
        logger.error(prefix_name(str), t);
    }

    private void warn(final String str) {
        logger.warn(prefix_name(str));
    }

    private String prefix_name(final String str) {
        return String.format("%s %s", name, str);
    }

    public String toString() {
        return String.format("IndexService(%s)", name);
    }

}
