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

import org.apache.commons.rng.simple.RandomSource

import java.util.concurrent.atomic.AtomicBoolean

// A stand-in for a real market data feed. One async task per symbol drips
// synthetic ticks into the registry. In a follow-up post we will replace
// this with a real feed pulled in parallel from a fleet of sources.
class FakeTickSource {
    TickerRegistry registry
    List<String> symbols
    long intervalMillis = 250

    private final running = new AtomicBoolean(false)
    private final rng = RandomSource.XO_RO_SHI_RO_128_PP.create(42L)

    void start() {
        running.set(true)
        symbols.each { sym ->
            var price = 50.0G + rng.nextInt(200)
            async {
                while (running.get()) {
                    var delta = (rng.nextInt(200) - 100) / 100.0
                    price = (price + delta).max(0.01g)
                    registry.publish(sym, price)
                    sleep intervalMillis
                }
            }
        }
    }

    void stop() {
        running.set(false)
    }
}
