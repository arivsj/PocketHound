package com.pockethound.app.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Uma linha de ferramenta pronta para a tela: rotulo e assunto.
 *
 * E o formato que o Harness usa no navegador, e a razao dele existir e simples:
 * o modelo escreve um campo \"description\" justamente para ser LIDO por um humano.
 * Jogar o JSON cru na tela ({\"code\":\"...\",\"description\":\"...\"}) esconde
 * exatamente a parte que foi escrita para voce.
 *
 * @param label rotulo curto da acao (\"Codigo\", \"Comando\", \"Leitura\"...).
 * @param subject assunto de uma linha — o que a acao faz, em palavras.
 */
data class ToolRow(val label: String, val subject: String)

/**
 * Traduz a chamada de ferramenta em rotulo + assunto.
 *
 * Funcao pura de proposito: e a parte que decide o que o usuario le, e ela muda
 * com o vocabulario das ferramentas — testar sem tela e sem rede e o que mantem
 * isso barato.
 */
object ToolSummary {

    /** Teto do assunto. Passou disso, a linha deixa de ser uma linha. */
    const val MAX_SUBJECT = 92

    /**
     * Rotulo humano da ferramenta.
     *
     * @param name nome tecnico (\"bash\", \"run_code\"...).
     * @return o rotulo que a tela mostra.
     */
    fun label(name: String): String = when (name) {
        "run_code" -> "Codigo"
        "bash" -> "Comando"
        "read" -> "Leitura"
        "write" -> "Escrita"
        "edit" -> "Edicao"
        "glob", "grep" -> "Busca"
        "todo_write" -> "Tarefas"
        "web_search" -> "Pesquisa"
        "vision_ask", "vision_warmup" -> "Visao"
        "subagent", "subagent_fork" -> "Subagente"
        "workflow" -> "Fluxo"
        "pockethound_notify", "pockethound_ask" -> "PocketHound"
        else -> "Ferramenta"
    }

    /**
     * Monta a linha inteira.
     *
     * @param name nome tecnico da ferramenta.
     * @param args argumentos ja analisados, como o PC mandou.
     * @return rotulo + assunto, nunca vazio.
     */
    fun of(name: String, args: JsonObject?): ToolRow = ToolRow(label(name), subject(name, args))

    /**
     * Assunto de uma linha para a chamada.
     *
     * A ordem das chaves e a ordem do que e mais util para quem le: primeiro o
     * que o modelo escreveu PARA o humano (\"description\"), depois o alvo da acao
     * (arquivo, padrao, consulta) e so no fim o nome cru da ferramenta.
     *
     * @param name nome tecnico da ferramenta.
     * @param args argumentos analisados.
     * @return assunto curto.
     */
    fun subject(name: String, args: JsonObject?): String {
        val chaves = listOf(
            "description",
            "file_path",
            "path",
            "pattern",
            "query",
            "url",
            "title",
            "command",
            "prompt",
            "question",
        )
        for (chave in chaves) {
            val valor = text(args, chave) ?: continue
            if (valor.isBlank()) continue
            val limpo = if (chave == "file_path" || chave == "path") encurtar(valor) else primeiraLinha(valor)
            return cortar(limpo)
        }
        return cortar(name)
    }

    /**
     * Assunto de um resultado, para a linha fechada.
     *
     * O corpo inteiro vive no detalhe; aqui cabe a primeira linha com conteudo,
     * que e o que diz se deu certo ou errado.
     *
     * @param texto resultado cru.
     * @return uma linha, ou uma marca de vazio.
     */
    fun resultSubject(texto: String): String {
        val linha = texto.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return cortar(linha ?: "(sem saida)")
    }

    /**
     * Caminho curto: os dois ultimos pedacos bastam para reconhecer o arquivo, e
     * o caminho inteiro nao cabe numa linha de celular.
     *
     * @param caminho caminho completo.
     * @return caminho encurtado pela esquerda quando for longo.
     */
    fun encurtar(caminho: String): String {
        val limpo = caminho.trim()
        if (limpo.length <= 48) return limpo
        val pedacos = limpo.split('/').filter { it.isNotEmpty() }
        if (pedacos.size <= 2) return limpo
        return ".../" + pedacos.takeLast(2).joinToString("/")
    }

    /**
     * Argumentos em JSON legivel, para o painel que abre no toque.
     *
     * O JSON cru de uma linha so e util para a maquina; quem abre o detalhe quer
     * ler. Sem isto o painel mostraria a chamada inteira espremida numa linha.
     *
     * @param args argumentos analisados.
     * @return JSON identado, ou string vazia quando nao ha argumentos.
     */
    fun pretty(args: JsonObject?): String {
        if (args == null || args.isEmpty()) return ""
        return runCatching { bonito.encodeToString(JsonObject.serializer(), args) }.getOrDefault(args.toString())
    }

    /** Codificador so para identar; o do protocolo escreve compacto de proposito. */
    private val bonito = Json { prettyPrint = true }

    /** Primeira linha com conteudo, sem indentacao nem excesso de espaco. */
    private fun primeiraLinha(texto: String): String =
        texto.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** Le uma chave de texto do JSON, tolerando numero e booleano. */
    private fun text(args: JsonObject?, chave: String): String? {
        val valor = args?.get(chave) as? JsonPrimitive ?: return null
        return valor.content
    }

    /** Corta preservando o comeco, que e onde esta a informacao. */
    private fun cortar(valor: String): String {
        val texto = valor.replace(Regex("\\s+"), " ").trim()
        if (texto.length <= MAX_SUBJECT) return texto
        return texto.take(MAX_SUBJECT - 1).trimEnd() + "..."
    }
}
