# Ambito di identità UDP (D-059)

UDP confronta due osservazioni quando coincidono tenant, classe canonica e categoria. Il confronto degli identificatori è esatto e distingue maiuscole e minuscole. La sorgente può essere diversa. La corrispondenza di ambito ammette l'oggetto alla ricerca dei candidati. Per collegare un UUID restano necessarie le evidenze previste dalla strategia pubblicata o la decisione HUMAN.

## Dichiarazione nel bundle

La categoria si dichiara nell'elemento di `semanticMapping.targetClasses[]` con `classIri` uguale alla classe canonica della risoluzione. L'esempio usa identificatori sintetici.

```json
{
  "ontologyId": "example",
  "ontologyVersion": "1",
  "classIri": "urn:example:PointOfInterest",
  "category": {
    "schemeIri": "urn:example:categories",
    "conceptIri": "urn:example:kiosk"
  }
}
```

Quando presente, `category` contiene esattamente `schemeIri` e `conceptIri`, entrambi stringhe con IRI assoluti. Il primo identifica il vocabolario controllato, il secondo il concetto. L'autorità sulla scelta del concetto è la mappatura pubblicata. UDP usa gli identificatori ricevuti senza risolverli in rete. Dichiarazioni ripetute della stessa classe devono concordare su entrambi gli IRI e sulla presenza della categoria. Anche l'eventuale `resolutionDefinition.governedIdentity.category` deve coincidere con la dichiarazione della classe target.

## Assenza e continuità

| Categoria della sorgente | Categoria dell'oggetto | Confronto a parità di tenant e classe |
|---|---|---|
| Assente | Assente | Ammesso |
| Assente | Presente | Escluso |
| Presente | Assente | Escluso |
| Presente | Presente | Ammesso solo con lo stesso `conceptIri` |

L'assenza si esprime omettendo `category`. Le forme JSON `null`, oggetto vuoto e stringa vuota sono invalide. Il concetto viene scritto in `urban_object.category_iri` alla creazione automatica o dopo un `CREATE_NEW` HUMAN. Una variazione del payload non riclassifica l'oggetto. Per Teatri e le altre sorgenti senza categoria restano i confronti fra oggetti senza categoria della stessa classe.

Il match diretto per chiave, la ricerca pesata e quella indicizzata filtrano per ambito prima di valutare ambiguità, limiti o punteggi. La revisione usa l'ambito registrato nelle evidenze della proposta. Le evidenze storiche prive di categoria corrispondono all'ambito senza categoria. L'approvazione ricontrolla l'oggetto corrente. La conferma di merge e la riallocazione dei binding nello split verificano la compatibilità degli UUID.

| Codice | Condizione ed effetto |
|---|---|
| `UDP_IDENTITY_CATEGORY_INVALID` | Dichiarazione malformata. Il caricamento della configurazione o il preflight si ferma. |
| `UDP_IDENTITY_CATEGORY_CONFLICT` | Dichiarazioni discordanti per la stessa classe o fra mappatura e policy. La configurazione viene rifiutata. |
| `UDP_IDENTITY_CATEGORY_DRIFT` | La categoria dell’oggetto di un binding attivo, altrimenti riutilizzabile, differisce da quella in arrivo. UDP nega la risoluzione prima di riutilizzare o scrivere il binding. Il worker registra il codice come errore permanente dell'handoff. |
| `UDP_IDENTITY_CATEGORY_MISMATCH` | Il candidato già proposto o gli UUID scelti per l'operazione HUMAN hanno categorie incompatibili. La transazione viene rifiutata. |

Nel pacchetto HUMAN una carta con ambito illeggibile resta aperta e riporta `UDP_RESOLUTION_ISSUE_SCOPE_UNPROVEN`. La conferma può applicare le scelte sulle carte disponibili. I riferimenti ai candidati restano quelli della proposta originale. Un candidato passato a un’altra categoria rimane visibile con `UDP_IDENTITY_CATEGORY_MISMATCH` e senza proprietà. Se la proposta aveva più candidati, la carta richiede una scelta esplicita.

Lo split richiede che originale e successori appartengano allo stesso tenant e alla stessa classe canonica, anche quando tutti sono senza categoria. È un cambiamento di comportamento voluto per l’isolamento del tenant e per D-059. Una differenza di tenant o classe produce `UDP_IDENTITY_SCOPE_MISMATCH`, una differenza di categoria produce `UDP_IDENTITY_CATEGORY_MISMATCH`. Verificare i piani di split in bozza prima del rilascio.

La ricerca rifiuta una discordanza tra policy e soggetto con `UDP_IDENTITY_SCOPE_INVALID`. Il motore verifica anche l’ambito della prova e di ogni candidato valutato, con `UDP_IDENTITY_POLICY_INVALID` in caso di discordanza.

## Coverage, attestazioni e migrazione

V37 aggiunge colonne nullable agli oggetti, alle quattro tabelle di lookup e alle attestazioni. Conserva i dati e le generazioni di coverage V36. Le chiavi di lookup usano `UNIQUE NULLS NOT DISTINCT` di PostgreSQL. Le ricerche usano uguaglianza esplicita fra valori nulli oppure, per gli indici di ricerca, la stessa espressione indicizzata con stringa vuota riservata. Per gli oggetti categorizzati la chiave canonica valorizzata è unica per ambito. Fra le righe senza categoria rimane il vincolo V1 sulla coppia classe e chiave canonica. Più oggetti possono avere chiave canonica assente.

Una policy senza categoria conserva il fingerprint precedente. Per una categoria dichiarata il fingerprint comprende entrambi gli IRI. Una configurazione che cambia categoria richiede il proprio preflight. La prova legata al vecchio hash e alla vecchia coverage resta riferita a quella configurazione. Un cambio del solo vocabolario a parità di concetto mantiene l'ambito di ricerca e richiede una nuova prova della policy.

Backfill, aggiornamento incrementale e invalidazioni operano sul singolo ambito. I lock transazionali di applicazione e trigger derivano dalla stessa terna. Una modifica della classificazione a livello dati invalida la coverage dell'ambito di origine e di destinazione. Alla migrazione non si assegnano categorie agli oggetti storici e non è richiesto un rebuild generale.

**Prerequisito di rilascio V36 → V37.** Fermare UDP e tutti gli scrittori V36 prima della migrazione. Mantenerli fermi fino al completamento di V37 e riavviare solo il runtime aggiornato. I due runtime usano chiavi di lock e vincoli di upsert diversi.
