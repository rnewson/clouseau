package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.binaryToString;
import static com.cloudant.cloujeau.OtpUtils.stringToBinary;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockFactory;

import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class IndexManagerService extends Service {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private final ExecutorService executor = Executors.newCachedThreadPool();

    public IndexManagerService(final ServerState state) {
        super(state, "main");
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws Exception {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            if (atom("open").equals(tuple.elementAt(0))) {
                return handleOpenCall(from, (OtpErlangTuple) request);
            }
        }
        return null;
    }

    private OtpErlangObject handleOpenCall(final OtpErlangTuple from, final OtpErlangTuple request) throws Exception {
        final OtpErlangPid peer = (OtpErlangPid) request.elementAt(1);
        final OtpErlangBinary path = (OtpErlangBinary) request.elementAt(2);
        final OtpErlangObject analyzerConfig = request.elementAt(3);

        final String strPath = binaryToString(path);
        logger.info(String.format("Opening index at %s", strPath));
        try {
            final IndexWriter writer = newWriter(path, analyzerConfig);
            final IndexService index = new IndexService(state, strPath, writer);
            executor.execute(index);
            index.link(peer);
            return tuple(atom("ok"), index.self());
        } catch (IOException e) {
            return tuple(atom("error"), stringToBinary(e.getMessage()));
        }
    }

    private IndexWriter newWriter(final OtpErlangBinary path, final OtpErlangObject analyzerConfig) throws Exception {
        final File rootDir = new File(state.config.getString("clouseau.dir", "target/indexes"));
        final Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
        final Directory dir = newDirectory(new File(rootDir, binaryToString(path)));
        final IndexWriterConfig writerConfig = new IndexWriterConfig(LuceneUtils.VERSION, analyzer);

        return new IndexWriter(dir, writerConfig);
    }

    private Directory newDirectory(final File path) throws ReflectiveOperationException {
        final String lockClassName = state.config
                .getString("clouseau.lock_class", "org.apache.lucene.store.NativeFSLockFactory");
        final Class<?> lockClass = Class.forName(lockClassName);
        final LockFactory lockFactory = (LockFactory) lockClass.getDeclaredConstructor().newInstance();

        final String dirClassName = state.config
                .getString("clouseau.dir_class", "org.apache.lucene.store.NIOFSDirectory");
        final Class<?> dirClass = Class.forName(dirClassName);
        return (Directory) dirClass.getDeclaredConstructor(File.class, LockFactory.class)
                .newInstance(path, lockFactory);
    }

}