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

import groovy.concurrent.BroadcastChannel
import groovy.transform.ActiveMethod
import groovy.transform.ActiveObject

import java.util.concurrent.Flow

@ActiveObject
class TickerRegistry {
    private final Map<String, BroadcastChannel> feeds = [:]
    private final Map<String, BigDecimal> latest = [:]

    @ActiveMethod
    Flow.Publisher<BigDecimal> subscribe(String symbol) {
        feeds.computeIfAbsent(symbol) { BroadcastChannel.create() }.asPublisher()
    }

    @ActiveMethod
    void publish(String symbol, BigDecimal price) {
        latest[symbol] = price
        feeds[symbol]?.send(price)
    }

    @ActiveMethod
    BigDecimal lastTick(String symbol) {
        latest[symbol]
    }

    @ActiveMethod
    void shutdown() {
        feeds.values()*.close()
        feeds.clear()
    }
}
