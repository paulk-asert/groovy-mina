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

import java.util.concurrent.atomic.AtomicBoolean

// Drop-in replacement for FakeTickSource from Part 1: same constructor
// shape (registry, symbols, intervalMillis), same start()/stop() API.
// On every tick interval, fan out to the fleet, aggregate the per-broker
// prices into a single consensus price (mean), and publish to the registry.
class FleetTickSource {
    TickerRegistry registry
    Fleet fleet
    List<String> symbols
    long intervalMillis = 1000

    private final running = new AtomicBoolean(false)

    void start() {
        running.set(true)
        async {
            while (running.get()) {
                var quotes = fleet.sweep(symbols)
                quotes.each { sym, prices ->
                    if (prices) {
                        var mean = prices.sum() / prices.size()
                        registry.publish(sym, mean)
                    }
                }
                sleep intervalMillis
            }
        }
    }

    void stop() {
        running.set(false)
    }
}
