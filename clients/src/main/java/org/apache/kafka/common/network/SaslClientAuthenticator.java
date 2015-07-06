/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.network;

import java.io.IOException;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;

import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import com.sun.security.auth.UserPrincipal;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;

import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.common.security.KerberosName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SaslClientAuthenticator implements Authenticator {

    private static final Logger LOG = LoggerFactory.getLogger(SaslClientAuthenticator.class);
    private SaslClient saslClient;
    private Subject subject;
    private String servicePrincipal;
    private String host;
    private int node = 0;
    private TransportLayer transportLayer;
    private NetworkReceive netInBuffer;
    private NetworkSend netOutBuffer;
    private byte[] saslToken = new byte[0];

    public enum SaslState {
        INITIAL,INTERMEDIATE,COMPLETE,FAILED
    }

    private SaslState saslState = SaslState.INITIAL;

    public SaslClientAuthenticator(Subject subject, TransportLayer transportLayer, String servicePrincipal, String host) throws IOException {
        this.transportLayer = transportLayer;
        this.subject = subject;
        this.host = host;
        this.servicePrincipal = servicePrincipal;
        this.saslClient = createSaslClient();
    }

    private SaslClient createSaslClient() {
        boolean usingNativeJgss =
            Boolean.getBoolean("sun.security.jgss.native");
        if (usingNativeJgss) {
            try {
                GSSManager manager = GSSManager.getInstance();
                Oid krb5Mechanism = new Oid("1.2.840.113554.1.2.2");
                GSSCredential cred = manager.createCredential(null,
                                                              GSSContext.INDEFINITE_LIFETIME,
                                                              krb5Mechanism,
                                                              GSSCredential.INITIATE_ONLY);
                subject.getPrivateCredentials().add(cred);
            } catch (GSSException ex) {
                LOG.warn("Cannot add private credential to subject; " +
                         "authentication at the server may fail", ex);
            }
        }

        final Object[] principals = subject.getPrincipals().toArray();
        // determine client principal from subject.
        final Principal clientPrincipal = (Principal)principals[0];
        final KerberosName clientKerberosName = new KerberosName(clientPrincipal.getName());
        // assume that server and client are in the same realm (by default; unless the system property
        // "kafka.server.realm" is set).
        String serverRealm = System.getProperty("kafka.server.realm",clientKerberosName.getRealm());
        KerberosName serviceKerberosName = new KerberosName(servicePrincipal+"@"+serverRealm);
        final String clientPrincipalName = clientKerberosName.toString();
        try {
            saslClient = Subject.doAs(subject,new PrivilegedExceptionAction<SaslClient>() {
                    public SaslClient run() throws SaslException {
                        LOG.debug("Client will use GSSAPI as SASL mechanism.");
                        String[] mechs = {"GSSAPI"};
                        LOG.debug("creating sasl client: client="+clientPrincipalName+";service="+servicePrincipal+";serviceHostname="+host);
                        SaslClient saslClient = Sasl.createSaslClient(mechs,clientPrincipalName,servicePrincipal,host,null,new ClientCallbackHandler(null));
                        return saslClient;
                    }
                });
            return saslClient;
        } catch (Exception e) {
            LOG.error("Exception while trying to create SASL client", e);
            return null;
        }
    }

    public int authenticate(boolean read, boolean write) throws IOException {
        if (netOutBuffer != null && !flushNetOutBuffer()) {
            return SelectionKey.OP_WRITE;
        }

        if(saslClient.isComplete()) {
            saslState = SaslState.COMPLETE;
            return 0;
        }

        byte[] serverToken = new byte[0];

        if(read && saslState == SaslState.INTERMEDIATE) {
            if (netInBuffer == null) netInBuffer = new NetworkReceive(node);
            long readLen = netInBuffer.readFrom(transportLayer);
            if (readLen == 0 || !netInBuffer.complete()) {
                return SelectionKey.OP_READ;
            } else {
                netInBuffer.payload().rewind();
                serverToken = new byte[netInBuffer.payload().remaining()];
                netInBuffer.payload().get(serverToken, 0, serverToken.length);
                netInBuffer = null; // reset the networkReceive as we read all the data.
            }
        } else if(saslState == SaslState.INITIAL) {
            saslState = SaslState.INTERMEDIATE;
        }

        if(!(saslClient.isComplete())) {
            try {
                saslToken = createSaslToken(serverToken);
                if (saslToken != null) {
                    netOutBuffer = new NetworkSend(node, ByteBuffer.wrap(saslToken));
                    if(!write || !flushNetOutBuffer()) {
                        return SelectionKey.OP_WRITE;
                    }
                }
            } catch(BufferUnderflowException be) {
                return SelectionKey.OP_READ;
            } catch(SaslException se) {
                saslState = SaslState.FAILED;
                throw new IOException("Unable to authenticate using SASL "+se);
            }
        }
        return SelectionKey.OP_READ;
    }

    public Principal principal() {
        return new UserPrincipal("ANONYMOUS");
    }

    public boolean isComplete() {
        return saslClient.isComplete() && saslState == SaslState.COMPLETE;
    }

    public void close() throws IOException {
        saslClient.dispose();
    }

    private byte[] createSaslToken(final byte[] saslToken) throws SaslException {
        if (saslToken == null) {
            throw new SaslException("Error in authenticating with a Kafka Broker: the kafka broker saslToken is null.");
        }

        try {
            final byte[] retval =
                Subject.doAs(subject, new PrivilegedExceptionAction<byte[]>() {
                        public byte[] run() throws SaslException {
                            return saslClient.evaluateChallenge(saslToken);
                        }
                    });
            return retval;
        } catch (PrivilegedActionException e) {
            String error = "An error: (" + e + ") occurred when evaluating Kafka Brokers " +
                      " received SASL token.";
            // Try to provide hints to use about what went wrong so they can fix their configuration.
            // TODO: introspect about e: look for GSS information.
            final String UNKNOWN_SERVER_ERROR_TEXT =
                "(Mechanism level: Server not found in Kerberos database (7) - UNKNOWN_SERVER)";
            if (e.toString().indexOf(UNKNOWN_SERVER_ERROR_TEXT) > -1) {
                error += " This may be caused by Java's being unable to resolve the Kafka Broker's" +
                    " hostname correctly. You may want to try to adding" +
                    " '-Dsun.net.spi.nameservice.provider.1=dns,sun' to your client's JVMFLAGS environment.";
            }
            error += " Kafka Client will go to AUTH_FAILED state.";
            LOG.error(error);
            throw new SaslException(error);
        }
    }

    private boolean flushNetOutBuffer() throws IOException {
        if (!netOutBuffer.completed()) {
            netOutBuffer.writeTo(transportLayer);
        }
        return netOutBuffer.completed();
    }

    public static class ClientCallbackHandler implements CallbackHandler {
        private String password = null;

        public ClientCallbackHandler(String password) {
            this.password = password;
        }

        public void handle(Callback[] callbacks) throws
          UnsupportedCallbackException {
            for (Callback callback : callbacks) {
                if (callback instanceof NameCallback) {
                    NameCallback nc = (NameCallback) callback;
                    nc.setName(nc.getDefaultName());
                }
                else {
                    if (callback instanceof PasswordCallback) {
                        PasswordCallback pc = (PasswordCallback)callback;
                        if (password != null) {
                            pc.setPassword(this.password.toCharArray());
                        } else {
                            LOG.warn("Could not login: the client is being asked for a password, but the Kafka" +
                              " client code does not currently support obtaining a password from the user." +
                              " Make sure -Djava.security.auth.login.config property passed to JVM and " +
                              " the client is configured to use a ticket cache (using" +
                              " the JAAS configuration setting 'useTicketCache=true)'. Make sure you are using" +
                              " FQDN of the Kafka broker you are trying to connect to. ");
                        }
                    }
                    else {
                        if (callback instanceof RealmCallback) {
                            RealmCallback rc = (RealmCallback) callback;
                            rc.setText(rc.getDefaultText());
                        }
                        else {
                            if (callback instanceof AuthorizeCallback) {
                                AuthorizeCallback ac = (AuthorizeCallback) callback;
                                String authid = ac.getAuthenticationID();
                                String authzid = ac.getAuthorizationID();
                                if (authid.equals(authzid)) {
                                    ac.setAuthorized(true);
                                } else {
                                    ac.setAuthorized(false);
                                }
                                if (ac.isAuthorized()) {
                                    ac.setAuthorizedID(authzid);
                                }
                            }
                            else {
                                throw new UnsupportedCallbackException(callback,"Unrecognized SASL ClientCallback");
                            }
                        }
                    }
                }
            }
        }
    }

}