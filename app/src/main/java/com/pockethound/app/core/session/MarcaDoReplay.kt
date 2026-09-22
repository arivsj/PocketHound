package com.pockethound.app.core.session

/**
 * Marca d'água do replay: o maior `seq` já dobrado na conversa.
 *
 * O `seq` nasce no PC e vale para o sistema inteiro — é a espinha do replay. O
 * celular guarda o último que processou e pede o buraco de volta quando a
 * conexão cai (ver [SessionClient]).
 *
 * Aqui ela responde a uma pergunta só, e responde grosseiro de propósito:
 * **este quadro já passou por aqui?** Quando o botão "atualizar" pede o reenvio
 * do começo do buffer do PC, tudo o que já está na tela volta junto, com o mesmo
 * número. Dobrar de novo duplicaria a conversa — e balão repetido com o mesmo
 * id derruba a lista inteira, porque o id é a chave de cada linha.
 *
 * O que fica de fora é só o descartável: um delta de texto perdido no meio do
 * caminho tem `seq` menor que a marca e não volta — mas o `text.done` do mesmo
 * passo consolidou o texto inteiro, então não há perda visível.
 */
class MarcaDoReplay {

    /** Maior `seq` já dobrado. Zero enquanto nada chegou. */
    var ultimo: Long = 0L
        private set

    /**
     * Este quadro é novidade?
     *
     * @param seq número do quadro, como o PC numerou.
     * @return true quando ele ainda não foi dobrado aqui.
     */
    fun aceita(seq: Long): Boolean = seq > ultimo

    /**
     * Registra um quadro dobrado. Nunca anda para trás: um replay de um trecho
     * antigo não pode reabrir a porta para o que já passou.
     *
     * @param seq número do quadro dobrado.
     */
    fun marcou(seq: Long) {
        if (seq > ultimo) ultimo = seq
    }

    /** Esquece tudo — pareamento desfeito, estado zerado. */
    fun limpar() {
        ultimo = 0L
    }
}
