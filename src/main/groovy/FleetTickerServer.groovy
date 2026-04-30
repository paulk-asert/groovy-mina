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
import java.time.Duration

// ----- Pick free local ports for our broker SSHD servers -----

var freePort = {
    var s = new ServerSocket(0)
    try { s.localPort } finally { s.close() }
}

var symbols = ['AAPL', 'GOOG', 'MSFT', 'TSLA']
var basePrices = [AAPL: 173.40g, GOOG: 198.20g, MSFT: 412.10g, TSLA: 215.55g]

// ----- Start three "broker boxes" (each is an Apache MINA SSHD server) -----

var brokers = (1..3).collect { i ->
    new BrokerBoxServer(name: "broker-$i", port: freePort(), basePrices: basePrices).tap { start() }
}
var hosts = brokers.collect { b ->
    new HostSpec(name: b.name, host: '127.0.0.1', port: b.port, user: 'demo', password: 'demo')
}
println "Started brokers on ports ${hosts*.port}"

// ----- Wire the same MINA hub from Part 1, but feed it from the fleet -----

var fleet = new Fleet(hosts: hosts)
var registry = new TickerRegistry()
var feed = new FleetTickSource(registry: registry, fleet: fleet, symbols: symbols, intervalMillis: 500)
feed.start()

var acceptor = new NioSocketAcceptor()
acceptor.filterChain.with {
    addLast 'codec',  new ProtocolCodecFilter(new TextLineCodecFactory(StandardCharsets.UTF_8))
    addLast 'logger', new LoggingFilter()
}
acceptor.handler = new TickerHandler(registry: registry)
acceptor.bind(new InetSocketAddress(0))
var hubPort = acceptor.localAddress.port
println "Ticker hub (fleet-backed) listening on port $hubPort"

// ----- Same in-process line-protocol client as Part 1 -----

var received = [].asSynchronized() as List<String>
var sock = new Socket('localhost', hubPort)
var reader = sock.inputStream.newReader('UTF-8')
var writer = new PrintWriter(sock.outputStream, true, StandardCharsets.UTF_8)

var reads = async {
    String line
    while ((line = reader.readLine()) != null) received << line
}

writer.println 'SUBSCRIBE AAPL,MSFT'
sleep 1500                                  // give a couple of fleet sweeps time to land
writer.println 'QUOTE AAPL,GOOG,MSFT,TSLA'
sleep 200
writer.println 'UNSUBSCRIBE AAPL'
sleep 600
writer.println 'QUIT'
await Awaitable.orTimeoutMillis(reads, 3000)

// ----- Tear down -----

feed.stop()
acceptor.unbind()
acceptor.dispose()
registry.shutdown()
fleet.close()
brokers*.stop()
sock.close()

// ----- Show what the client saw, and assert the protocol behaved -----

println '--- received ---'
received.each { println it }

assert received.contains('SUBSCRIBED AAPL')
assert received.contains('SUBSCRIBED MSFT')
assert received.any { it.startsWith('TICK AAPL ') }
assert received.any { it.startsWith('TICK MSFT ') }
assert received.findAll { it.startsWith('QUOTE ') }.size() == 4
assert received.contains('UNSUBSCRIBED AAPL')
assert received.last() == 'BYE'
println 'OK'
