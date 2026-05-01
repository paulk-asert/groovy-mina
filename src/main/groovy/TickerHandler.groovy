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
import org.apache.mina.core.service.IoHandlerAdapter
import org.apache.mina.core.session.IoSession

// Bridges MINA's callback-driven IoHandler model into Groovy 6's
// async/channel world. This class deliberately contains zero protocol
// logic -- it just opens a per-session inbox channel, forwards incoming
// messages into it, and lets a single async task drive the protocol
// linearly with `for`. See TickerProtocol for that task.
class TickerHandler extends IoHandlerAdapter {
    static final String INBOX = 'inbox'
    static final String TASK  = 'task'

    TickerRegistry registry

    @Override
    void sessionOpened(IoSession session) {
        // Bounded buffer; provides back-pressure if the protocol task
        // falls behind the network read rate. Sized generously so the
        // NIO thread calling messageReceived never blocks in practice.
        var inbox = AsyncChannel.create(64)
        session.setAttribute(INBOX, inbox)
        // The protocol task's lifetime is bound to the session. We cannot
        // await it here -- this method runs on MINA's NIO thread -- so we
        // observe completion via whenComplete (so failures cannot be
        // silently swallowed) and stash the Awaitable on the session so
        // sessionClosed/exceptionCaught can cancel it as a backstop.
        var task = async {
            new TickerProtocol(session: session, inbox: inbox, registry: registry).run()
        }
        task.whenComplete { _, throwable ->
            if (throwable) exceptionCaught(session, throwable)
        }
        session.setAttribute(TASK, task)
    }

    @Override
    void messageReceived(IoSession session, message) {
        session.getAttribute(INBOX).send(message.toString())
    }

    @Override
    void sessionClosed(IoSession session) {
        session.getAttribute(INBOX)?.close()
        session.getAttribute(TASK)?.cancel()
    }

    @Override
    void exceptionCaught(IoSession session, Throwable cause) {
        cause.printStackTrace()
        session.getAttribute(INBOX)?.close()
        session.getAttribute(TASK)?.cancel()
        session.closeNow()
    }
}
