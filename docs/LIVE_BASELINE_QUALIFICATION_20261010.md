# Qualificazione dalla baseline live — 10 ottobre 2026

Base immutabile UDP 83249a897eb4add4289b5181b3299f48ea4c0f99, schema V1–V34 e motore class-neutral governato già consegnati. Il candidato conserva tutte le migrazioni, IAM, catalogo, review package, recovery e motore. Non è un deploy di main: nessun rollback involontario a V21 o al solo resolver storico.

Porta la qualificazione dipendenze già provata su main: Boot4.0.8, Tomcat11.0.26, Jackson2.21.7 tramite modulo transitorio e preferenza mapper; Flyway starter esplicito e test MVC nella nuova posizione. Conserva il Resource Server IAM con starter Boot4, senza disabilitare autenticazione. MockBean rimosso in Boot4 passa a MockitoBean del TestContext; stesso oggetto simulato e stesse asserzioni. Il filtro disabilitato nel test recovery è una fixture preesistente, non una nuova eccezione.

Lo scanner ora include gli HIGH/CRITICAL non ancora corretti; nessuna soppressione o riduzione gate. Il MinIO binario upstream verificato e la fixture PITR già presenti nella base sono preservati.

Prima del rilascio servono CI della combinazione effettiva, test natura/tenant sul resolver legacy e governato, merge HUMAN, migrazione N/N+1, conservazione dei byte V1–V34 e firme/artefatti/custodia target. Le nuove 14 prove main non si ereditano automaticamente. Non replay Cinema, R9 o input consumati; non ri-upload Teatri.

Questa revisione non cambia SQL, motore, policy, JAR/immagine live o autorità di installazione. Resta candidato, non release acceptance.

Riferimenti: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide ; https://docs.spring.io/spring-boot/4.0/reference/features/json.html ; main qualificato c7a158a9d8b9f514fd2d9dd6e80a659a407e1b96.

## Guard identità trasferiti selettivamente

I 14 nuovi casi main sono aggiunti, conservando tutte le suite governate/recovery preesistenti. Resolver storico: binding/replay validano natura, tenant e stato, nessun target per REVIEW non risolta; una review HUMAN risolta resta riprendibile con scope ricontrollato. Query candidati e binding sono serializzati senza cambiare WeightedIdentity/GovernedIdentityEngine o la policy pubblicata. Decisioni legacy nuove conservano scope nella prova append-only.

Merge pianificato/eseguito richiede natura/tenant compatibili e tenant HUMAN; l'esecuzione conserva le scope lock avanzate. L'approvazione legacy legge lo scope dalla decisione immutabile, prende le lock nello stesso ordine e ricontrolla il target. Il percorso GOVERNED_IDENTITY conserva le prove strutturate, coverage e riapertura review stale già installati; nessuna sostituzione con stringhe legacy. Errori/binding conflict esistenti restano stabili.

Le prove devono essere eseguite sul nuovo SHA prima di qualificare il candidato. Nessun apply target o nuovo gate storico chiuso.
