package com.pockethound.app.core.transport

/**
 * Conta sondas de rede mortas em sequencia e diz quando vale nascer de novo.
 *
 * O runtime QUIC do iroh nasce vinculado a rede de quando foi criado. Quando a
 * rede muda por baixo (o celular saiu de casa, o IP do PC girou no 4G), o
 * endpoint antigo nao se recupera sozinho: ele tenta para sempre sem abrir
 * conexao nenhuma — foi exatamente o defeito de campo de 23/09, quando o app
 * ficou horas dizendo "sem ponte" com o PC saudavel e o relay respondendo.
 *
 * Recriar o endpoint a CADA falha seria desperdicio (rede ruim e comum na rua);
 * esperar SEMPRE seria nunca se recuperar. O meio termo e contar: [limite]
 * sondas mortas seguidas = uma vez, nasce de novo. O sucesso zera a contagem.
 *
 * @param limite mortas seguidas antes de disparar.
 */
class Autocura(private val limite: Int = 3) {

    /** Mortas desde a ultima viva. */
    private var seguidas = 0

    /** Uma sonda respondeu: o caminho esta vivo, recomeca a contagem. */
    @Synchronized
    fun sucesso() {
        seguidas = 0
    }

    /**
     * Uma sonda morreu.
     *
     * @return true quando esta e a [limite]-esima morta seguida — a hora de
     * recriar o endpoint. A contagem zera junto, para a proxima leva.
     */
    @Synchronized
    fun falhou(): Boolean {
        seguidas += 1
        if (seguidas < limite) return false
        seguidas = 0
        return true
    }
}
