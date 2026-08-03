/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

object ProtocolStatus {
    fun names(
        wfas: Boolean,
        rtp: Boolean,
        http: Boolean,
        dlna: Boolean,
        snapcast: Boolean = false
    ): List<String> {
        val out = ArrayList<String>(5)
        if (wfas) out.add("WFAS")
        if (rtp) out.add("RTP")
        if (http) out.add("HTTP")
        if (dlna) out.add("DLNA")
        if (snapcast) out.add("Snapcast")
        return out
    }

    fun join(items: List<String>, conjunction: String): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " " + conjunction + " " + items.last()
    }

    fun summary(
        wfas: Boolean,
        rtp: Boolean,
        http: Boolean,
        dlna: Boolean,
        conjunction: String,
        snapcast: Boolean = false
    ): String = join(names(wfas, rtp, http, dlna, snapcast), conjunction)
}
