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

import groovy.concurrent.AsyncChannel
import groovy.concurrent.AsyncScope
import org.apache.mina.core.session.IoSession

import java.util.concurrent.atomic.AtomicBoolean

// All wire-protocol logic for a single client session, written
// top-to-bottom as plain async Groovy. Because the IoHandler does
// nothing but funnel inbound messages into `inbox`, we get to express
// the entire conversation as one straight read loop with structured
// fan-out for QUOTE.
class TickerProtocol {
    IoSession session
    AsyncChannel<String> inbox
    TickerRegistry registry

    private final Map<String, AtomicBoolean> subs = [:]

    void run() {
        AsyncScope.withScope {
            for (String line in inbox) {
                var parts = line.trim().split(/\s+/, 2)
                var cmd  = parts[0].toUpperCase()
                var args = parts.length > 1 ? parts[1] : ''
                switch (cmd) {
                    case 'SUBSCRIBE'   -> handleSubscribe(args)
                    case 'UNSUBSCRIBE' -> handleUnsubscribe(args)
                    case 'QUOTE'       -> handleQuote(args)
                    case 'QUIT'        -> session.with{ write('BYE'); closeOnFlush() }
                    default            -> session.write("ERROR unknown command: $cmd")
                }
            }
        }
        // Scope exit guarantees every per-symbol subscription task has
        // finished or been cancelled before run() returns.
    }

    private void handleSubscribe(String args) {
        symbolsOf(args).each { sym ->
            if (subs.containsKey(sym)) return
            var cancel = new AtomicBoolean(false)
            subs[sym] = cancel
            async {
                for await (price in registry.subscribe(sym)) {
                    if (cancel.get()) break
                    session.write("TICK $sym $price")
                }
            }
            session.write("SUBSCRIBED $sym")
        }
    }

    private void handleUnsubscribe(String args) {
        symbolsOf(args).each { sym ->
            subs.remove(sym)?.set(true)
            session.write("UNSUBSCRIBED $sym")
        }
    }

    // Structured fan-out: every symbol is fetched in its own async task
    // bound to a fresh scope. If any one fails, siblings are cancelled
    // before the scope exits -- no leaked tasks, no lingering threads.
    private void handleQuote(String args) {
        var syms = symbolsOf(args)
        AsyncScope.withScope {
            var lookups = syms.collect { sym -> async { [sym, registry.lastTick(sym)] } }
            lookups.each { l ->
                var (sym, price) = await(l)
                session.write("QUOTE $sym ${price ?: 'NA'}")
            }
        }
    }

    private static List<String> symbolsOf(String args) {
        args.split(',')*.trim()
    }
}
