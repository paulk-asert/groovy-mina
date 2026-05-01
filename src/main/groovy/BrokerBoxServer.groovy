/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

// A locally-hosted Apache MINA SSHD server standing in for a "broker box"
// in our fleet. Each broker carries its own slightly noisy view of the
// market and replies to a single command: `quote SYM1,SYM2,...`. Real
// fleets would obviously authenticate properly and expose a richer shell.
class BrokerBoxServer {
    String name
    int port
    Map<String, BigDecimal> basePrices

    private SshServer sshd
    private final rng = RandomSource.XO_RO_SHI_RO_128_PP.create()

    void start() {
        sshd = SshServer.setUpDefaultServer()
        sshd.host = '127.0.0.1'
        sshd.port = port
        sshd.keyPairProvider = new SimpleGeneratorHostKeyProvider()
        sshd.passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
        sshd.commandFactory = { ChannelSession ch, String line ->
            new QuoteCommand(commandLine: line, basePrices: basePrices, rng: rng)
        } as CommandFactory
        // Post-handshake hook: in production this is where you'd install
        // session-scoped audit, tighten algorithms, attach resource limits, etc.
        sshd.addSessionListener(new SessionListener() {
            @Override
            void sessionEvent(Session session, SessionListener.Event event) {
                if (event == SessionListener.Event.KeyEstablished) {
                    println "[$name] SECURED ${session.ioSession.remoteAddress}"
                }
            }
        })
        sshd.start()
    }

    void stop() {
        sshd?.stop()
    }

    // The minimal SSHD Command shape: hold the wire streams, run the work
    // on a virtual thread when start() is invoked, and signal completion
    // through the ExitCallback.
    private static class QuoteCommand implements Command {
        String commandLine
        Map<String, BigDecimal> basePrices
        UniformRandomProvider rng

        InputStream input
        OutputStream out
        OutputStream err
        ExitCallback callback

        @Override void setInputStream(InputStream value) { input = value }
        @Override void setOutputStream(OutputStream value) { out = value }
        @Override void setErrorStream(OutputStream value) { err = value }
        @Override void setExitCallback(ExitCallback value) { callback = value }
        @Override void destroy(ChannelSession channel) { }

        @Override
        void start(ChannelSession channel, Environment env) {
            async {
                try {
                    var parts = commandLine.trim().split(/\s+/, 2)
                    if (parts[0] != 'quote' || parts.length < 2) {
                        err.write("usage: quote SYM1,SYM2,...\n".getBytes('UTF-8'))
                        callback.onExit(2)
                        return
                    }
                    parts[1].split(',')*.trim().each { sym ->
                        var base = basePrices[sym]
                        if (base == null) return
                        var jitter = (rng.nextInt(40) - 20) / 100.0
                        var price = base + jitter
                        out.write("$sym $price\n".getBytes('UTF-8'))
                    }
                    out.flush()
                    callback.onExit(0)
                } catch (Throwable t) {
                    callback.onExit(1, t.message ?: 'error')
                }
            }
        }
    }
}
