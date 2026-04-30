<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
## Streaming with Apache MINA and Groovy 6

A small streaming "ticker hub" built on Apache MINA, refactored around
Groovy 6's `async`/`await`, `AsyncChannel`, `BroadcastChannel`,
`@ActiveObject` and `AsyncScope` so the protocol logic reads top-to-bottom
instead of being scattered across `IoHandler` callbacks.

Two entry points:

* `TickerServer.groovy` -- the Part 1 demo. Hub plus a stubbed local feed.
* `FleetTickerServer.groovy` -- the Part 2 demo. Same hub, but the feed comes from
  a fleet of three Apache MINA SSHD "broker boxes" using `collectParallel` and
  `Pool.virtual()` for the fan-out.

Examples for:
* Part 1: https://groovy.apache.org/blog/groovy-mina
* Part 2: https://groovy.apache.org/blog/groovy-mina-sshd

Requires JDK 21+ (for virtual threads) and Groovy 6.
