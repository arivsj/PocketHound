package com.pockethound.app.core.session

import com.pockethound.app.core.model.TodoItem

/**
 * O retrato do plano do turno, para o botao fechado do painel de tarefas.
 *
 * O plano chega inteiro do PC (projecao `todos` do Harness, a mesma lista que o
 * navegador desenha), mas fechado o painel so tem uma linha para dizer o
 * essencial: quantas ja foram e o que esta acontecendo agora. Contar isso e uma
 * regra pura, entao ela vive aqui e nao no desenho da tela.
 *
 * @param total quantas tarefas o plano tem.
 * @param feitas quantas estao concluidas.
 * @param atual a tarefa em andamento; sem nenhuma em andamento, a proxima
 *   pendente; e nulo quando nao ha nem uma nem outra.
 */
data class ProgressoDoPlano(
    val total: Int,
    val feitas: Int,
    val atual: String?,
) {
    /** "2/5" — o que cabe no botao fechado. */
    val contagem: String get() = feitas.toString() + "/" + total

    /** Terminou tudo o que o plano pedia? */
    val concluido: Boolean get() = total > 0 && feitas == total

    companion object {
        /**
         * Conta o plano.
         *
         * Status desconhecido conta como pendente de proposito: uma versao mais
         * nova do Harness pode inventar um estado, e some-lo como "feito" seria
         * mentir sobre o progresso — contar como "falta fazer" erra para o lado
         * que nao promete nada.
         *
         * @param todos o plano, como veio do PC.
         * @return o retrato para o botao.
         */
        fun de(todos: List<TodoItem>): ProgressoDoPlano {
            val feitas = todos.count { it.status == STATUS_CONCLUIDA }
            val andando = todos.firstOrNull { it.status == STATUS_EM_ANDAMENTO }
            val proxima = todos.firstOrNull { it.status != STATUS_CONCLUIDA }
            return ProgressoDoPlano(
                total = todos.size,
                feitas = feitas,
                atual = (andando ?: proxima)?.content,
            )
        }
    }
}

/** Os status que o Harness usa em `todo_write`. */
private const val STATUS_CONCLUIDA = "completed"
private const val STATUS_EM_ANDAMENTO = "in_progress"
