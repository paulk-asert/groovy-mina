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

import groovy.concurrent.ParallelScope
import groovy.concurrent.Pool
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier

import java.time.Duration

// A pool of broker boxes accessible over SSH. Each `sweep` fans out
// across every host in parallel, asks for the latest price for each
// symbol, and returns a per-symbol list of prices (one per box).
class Fleet implements AutoCloseable {
    List<HostSpec> hosts

    private final SshClient client = SshClient.setUpDefaultClient()

    Fleet() {
        client.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
        client.start()
    }

    Map<String, List<BigDecimal>> sweep(List<String> symbols) {
        // Pool.virtual() = one virtual thread per host. SSH I/O is
        // overwhelmingly blocking and parking, exactly what virtual
        // threads were designed for.
        var perHost = ParallelScope.withPool(Pool.virtual()) { scope ->
            hosts.collectParallel { host ->
                try {
                    fetchQuotes(host, symbols)
                } catch (Throwable t) {
                    System.err.println "WARN ${host.name}: ${t.message}"
                    [:]
                }
            }
        }

        // Pivot List<Map<sym,price>> into Map<sym, List<price>>.
        symbols.collectEntries { sym ->
            [(sym): perHost*.get(sym)]
        }
    }

    private Map<String, BigDecimal> fetchQuotes(HostSpec host, List<String> symbols) {
        var session = client.connect(host.user, host.host, host.port)
                .verify(Duration.ofSeconds(2)).session
        try {
            session.addPasswordIdentity(host.password)
            session.auth().verify(Duration.ofSeconds(2))
            var out = new ByteArrayOutputStream()
            var channel = session.createExecChannel("quote ${symbols.join(',')}")
            try {
                channel.out = out
                channel.open().verify(Duration.ofSeconds(2))
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(5))
            } finally {
                channel.close(false)
            }
            parseQuotes(out.toString('UTF-8'))
        } finally {
            session.close(false)
        }
    }

    private static Map<String, BigDecimal> parseQuotes(String text) {
        Map<String, BigDecimal> result = [:]
        text.readLines().each { line ->
            var parts = line.trim().split(/\s+/, 2)
            if (parts.length == 2) result[parts[0]] = parts[1].toBigDecimal()
        }
        result
    }

    @Override
    void close() {
        client?.stop()
    }
}
