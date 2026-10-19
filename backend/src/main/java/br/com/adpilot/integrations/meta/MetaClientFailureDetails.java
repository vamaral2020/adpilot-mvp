package br.com.adpilot.integrations.meta;

import org.springframework.web.client.RestClientException;

/** Diagnostico seguro: expõe apenas nomes de classes, sem mensagens de erro. */
record MetaClientFailureDetails(String clientException, String rootException) {
    static MetaClientFailureDetails from(RestClientException error) {
        Throwable deepest = error;
        // Limite defensivo para cadeias de causa malformadas/ciclicas.
        for (int i = 0; i < 16 && deepest.getCause() != null
                && deepest.getCause() != deepest; i++) {
            deepest = deepest.getCause();
        }
        return new MetaClientFailureDetails(
                error.getClass().getSimpleName(), deepest.getClass().getSimpleName());
    }
}
