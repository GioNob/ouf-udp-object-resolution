package it.comune.trieste.ouf.udp;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** The scoped scan was O(objects in tenant/class) per handoff and is disabled.
 * Until an inverted canonical-value index has verified coverage, automatic
 * identity resolution must fail closed.
 */
@Repository
public class ScopedIdentityCandidateRepository {
  public GovernedIdentityEngine.Candidates retrieve(GovernedIdentityEngine.Policy policy) {
    Objects.requireNonNull(policy);
    return new GovernedIdentityEngine.Candidates(policy.ref(),policy.version(),policy.tenantId(),
        policy.canonicalClass(),null,false,List.of());
  }
}
