package com.pockethound.app.core.model

/**
 * Conteúdo do QR Code gerado pelo PocketHound desk (ARQUITETURA.md §8).
 *
 * Esquema: `pockethound://pair?v=1&n=<nome>&t=<ticket>&k=<chave>&x=<expira>&a=<endereço>&c=<código>`
 *
 * Parser em Kotlin puro (sem android.net.Uri) para poder ser testado na JVM.
 *
 * `a` e `c` são **opcionais**: sem eles o pareamento continua funcionando com o
 * endereço e o código digitados à mão, que é o caminho que funciona hoje. Com o
 * QR, os dois vêm preenchidos e o usuário só confirma.
 */
data class PairingPayload(
    val version: Int,
    val pcName: String?,
    val ticket: String,
    val key: String,
    val expiresAt: Long,
    /** Endereço direto do desk (`http://192.168.0.10:7411`). */
    val address: String? = null,
    /** Código de pareamento de 6 dígitos mostrado no PC. */
    val code: String? = null,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAt

    companion object {
        const val SCHEME = "pockethound"
        const val HOST = "pair"
        const val VERSION = 1

        /** Devolve null para qualquer payload que não seja um pareamento válido. */
        fun parse(raw: String?): PairingPayload? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            val schemeEnd = text.indexOf("://")
            if (schemeEnd <= 0) return null
            if (!SCHEME.equals(text.substring(0, schemeEnd), ignoreCase = true)) return null

            val rest = text.substring(schemeEnd + 3)
            val queryStart = rest.indexOf('?')
            val authority = (if (queryStart >= 0) rest.substring(0, queryStart) else rest)
                .substringBefore('/')
                .lowercase()
            if (authority != HOST) return null

            val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""
            val params = LinkedHashMap<String, String>()
            query.split('&').forEach { chunk ->
                if (chunk.isEmpty()) return@forEach
                val separator = chunk.indexOf('=')
                val key = if (separator >= 0) chunk.substring(0, separator) else chunk
                val value = if (separator >= 0) chunk.substring(separator + 1) else ""
                val decodedKey = decode(key)
                // primeiro valor vence: parâmetro repetido no QR é sinal de payload adulterado
                if (!params.containsKey(decodedKey)) params[decodedKey] = decode(value)
            }

            val version = params["v"]?.trim()?.toIntOrNull() ?: return null
            if (version != VERSION) return null

            val ticket = params["t"]?.trim().orEmpty()
            if (ticket.isEmpty()) return null

            val key = params["k"]?.trim().orEmpty()
            if (key.isEmpty()) return null

            val expiresAt = params["x"]?.trim()?.toLongOrNull() ?: return null
            if (expiresAt <= 0L) return null

            // O endereço só entra se for http(s): um esquema estranho aqui viraria
            // uma requisição para onde o QR mandasse.
            val address = params["a"]?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

            // O código só entra com exatamente 6 dígitos — a mesma regra do PC.
            val code = params["c"]?.trim()?.takeIf { it.length == 6 && it.all { char -> char.isDigit() } }

            return PairingPayload(
                version = version,
                pcName = params["n"]?.trim()?.takeIf { it.isNotEmpty() },
                ticket = ticket,
                key = key,
                expiresAt = expiresAt,
                address = address,
                code = code,
            )
        }

        private fun decode(value: String): String =
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
