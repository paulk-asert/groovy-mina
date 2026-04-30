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


import groovy.concurrent.Awaitable
import org.apache.mina.filter.codec.ProtocolCodecFilter
import org.apache.mina.filter.codec.textline.TextLineCodecFactory
import org.apache.mina.filter.logging.LoggingFilter
import org.apache.mina.transport.socket.nio.NioSocketAcceptor

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

// ----- Bring up the registry, the fake feed, and the MINA acceptor -----

var symbols = ['AAPL', 'GOOG', 'MSFT', 'TSLA']
var registry = new TickerRegistry()
var feed = new FakeTickSource(registry: registry, symbols: symbols, intervalMillis: 200)
feed.start()

var acceptor = new NioSocketAcceptor()
acceptor.filterChain.with {
    addLast 'codec',  new ProtocolCodecFilter(new TextLineCodecFactory(StandardCharsets.UTF_8))
    addLast 'logger', new LoggingFilter()
}
acceptor.handler = new TickerHandler(registry: registry)
acceptor.bind(new InetSocketAddress(0))    // ephemeral port

var port = acceptor.localAddress.port
println "Ticker hub listening on port $port"

// ----- A tiny in-process line-protocol client to drive the demo -----

var received = [].asSynchronized()
var sock = new Socket('localhost', port)
var reader = sock.inputStream.newReader('UTF-8')
var writer = new PrintWriter(sock.outputStream, true, StandardCharsets.UTF_8)

var reads = async {
    String line
    while ((line = reader.readLine()) != null) received << line
}

writer.println 'SUBSCRIBE AAPL,MSFT'
sleep 700                        // collect a few TICKs
writer.println 'QUOTE AAPL,GOOG,MSFT,TSLA'  // structured fan-out
sleep 200
writer.println 'UNSUBSCRIBE AAPL'           // one half stops streaming
sleep 400
writer.println 'QUIT'
await Awaitable.orTimeoutMillis(reads, 5000)
// ----- Tear down -----

feed.stop()
acceptor.unbind()
acceptor.dispose()
registry.shutdown()
sock.close()

// ----- Show what the client saw, and assert the protocol behaved -----

println '--- received ---'
received.each { println it }

assert received.first() == 'SUBSCRIBED AAPL'
assert received.contains('SUBSCRIBED MSFT')
assert received.any { it.startsWith('TICK AAPL ') }
assert received.any { it.startsWith('TICK MSFT ') }
assert received.findAll { it.startsWith('QUOTE ') }.size() == 4
assert received.contains('UNSUBSCRIBED AAPL')
assert received.last() == 'BYE'
println 'OK'
