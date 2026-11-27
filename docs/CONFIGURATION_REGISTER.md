# Registro della configurazione UDP

## udp.publication-reader@1

Il custode dell'installazione UDP gestisce questi parametri nella configurazione runtime (ConfigMap o ambiente equivalente). La classe è configurazione. I parametri sono numerici e pubblicabili come schema. I valori effettivi appartengono alla distinta privata dell'installazione. Il riferimento al token M2M resta in custodia separata.

| Parametro | Unità e default del modulo | Uso |
|---|---|---|
| `OUF_UDP_PUBLICATION_BUNDLE_BYTES` | byte, 3145728 | Limite della busta HTTP completa, applicato durante la ricezione. Onboarding usa il minimo dei lettori UDP e Ingestion. |
| `OUF_UDP_PUBLICATION_MAX_REFERENCES` | riferimenti, 2049 | Profilo di capacità per una classe, 1024 proprietà di colonna e 1024 uscite di composizione. Dimensionare anche per i pin di tipi annidati e vocabolari della pubblicazione. |
| `OUF_UDP_REFERENCE_BATCH_REQUEST_BYTES` | byte, 262144 | Limite incluso del JSON UTF-8 effettivamente inviato, riducibile fino a 1. |
| `OUF_UDP_REFERENCE_BATCH_ITEMS` | elementi, 1000 | Massimo per POST, configurabile da 1 a 1000. |
| `OUF_UDP_REFERENCE_VERIFICATION_ATTEMPTS` | verifiche, 3 | Numero totale di letture coerenti tentate in caso di cambio di generazione, da 1 a 128. |
| `OUF_UDP_PUBLICATION_GATEWAY_INTERVAL_MS` | ms, 50 | Intervallo locale minimo tra inizi di richieste della stessa rotta di pubblicazione o lotto. |
| `OUF_UDP_REFERENCE_GET_INTERVAL_MS` | ms, 50 | Intervallo locale minimo sulla rotta GET puntuale. |
| `OUF_UDP_GATEWAY_RETRY_MAX_DELAY_MS` | ms, 60000 | Tetto dell'attesa chiesta dal Gateway dopo un `429` o `503`: prima `Retry-After`, poi `X-RateLimit-Reset` (APISIX `limit-count` non emette `Retry-After`), altrimenti il dosaggio durevole. |
| `OUF_UDP_GATEWAY_RETRY_JITTER_MS` | ms, 1000 | Jitter massimo aggiunto all'attesa, limitato a un decimo della finestra. |
| `OUF_UDP_PUBLICATION_RECOVERY_MAX_ATTEMPTS` | tentativi, 128 | Budget durevole per episodio del job, da 1 a 128. |
| `OUF_UDP_PUBLICATION_RECOVERY_MAX_ELAPSED_MS` | ms, 86400000 | Scadenza complessiva, da 1 ms a 24 ore. |

Catturare le chiavi effettive con la revisione della ConfigMap, il digest dell'immagine e la versione del contratto. Ripristinarle prima di avviare il worker, insieme al binding del Gateway e del tenant. Il journal di recupero del job resta nello schema UDP e va ripristinato con il database. Per la prova di ripristino verificare i limiti di byte sul filo, il rifiuto di una busta oltre il limite e la ripresa con un nuovo worker dal tentativo persistito. Le prove del modulo sono `SemanticReferenceBatchTest` e `PublicationDependencyRecoveryRuntimeTest`. I default sono definiti in [application.yml](../src/main/resources/application.yml).
