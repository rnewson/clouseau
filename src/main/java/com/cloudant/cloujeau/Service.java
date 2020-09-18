package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.reply;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;

import org.apache.log4j.Logger;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangDecodeException;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpMsg;

public abstract class Service implements Runnable {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private static final OtpErlangObject INVALID_MSG = tuple(atom("error"), atom("invalid_msg"));

    protected final ServerState state;
    private final OtpMbox mbox;

    public Service(final ServerState state, final String serviceName) {
        this(state, state.node.createMbox(serviceName));
    }

    public Service(final ServerState state) {
        this(state, state.node.createMbox());
    }

    private Service(final ServerState state, final OtpMbox mbox) {
        this.state = state;
        this.mbox = mbox;
    }

    public void run() {
        loop: while (true) {
            try {
                final OtpMsg msg = mbox.receiveMsg();
                switch (msg.type()) {
                case OtpMsg.sendTag:
                case OtpMsg.regSendTag: {
                    final OtpErlangObject obj = msg.getMsg();
                    if (obj instanceof OtpErlangTuple) {
                        final OtpErlangTuple tuple = (OtpErlangTuple) obj;
                        final OtpErlangAtom atom = (OtpErlangAtom) tuple.elementAt(0);

                        // gen_call
                        if (atom("$gen_call").equals(atom)) {
                            final OtpErlangTuple from = (OtpErlangTuple) tuple.elementAt(1);
                            final OtpErlangObject request = tuple.elementAt(2);

                            final OtpErlangObject response = handleCall(from, request);
                            if (response != null) {
                                reply(mbox, from, response);
                            } else {
                                reply(mbox, from, INVALID_MSG);
                            }
                        }
                        // gen cast
                        else if (atom("$gen_cast").equals(atom)) {
                            final OtpErlangObject request = tuple.elementAt(1);
                            handleCast(request);
                        }
                    } else {
                        // handle info
                        handleInfo(obj);
                    }
                }
                    break;

                default:
                    logger.warn("received message of unknown type " + msg.type());
                }
            } catch (OtpErlangExit e) {
                logger.error(this + " exiting", e);
                terminate(e.reason());
                mbox.close();
                break loop;
            } catch (Exception e) {
                logger.error(this + " exiting", e);
                terminate(atom("exit"));
                mbox.close();
                break loop;
            }
        }
    }

    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws Exception {
        return null;
    }

    public void handleCast(final OtpErlangObject request) throws Exception {
    }

    public void handleInfo(final OtpErlangObject request) throws Exception {
    }

    public void terminate(final OtpErlangObject reason) {
        // Intentionally empty.
    }

    public final void link(final OtpErlangPid pid) throws OtpErlangExit {
        mbox.link(pid);
    }

    public final void unlink(final OtpErlangPid pid) {
        mbox.unlink(pid);
    }

    public final void exit(final OtpErlangObject reason) throws IOException {
        mbox.exit(reason);
        terminate(reason);
    }

    public OtpErlangPid self() {
        return mbox.self();
    }

    public String toString() {
        final String name = mbox.getName();
        if (name == null) {
            return String.format("Service(%s)", mbox.self());
        } else {
            return String.format("Service(%s)", name);
        }
    }
}