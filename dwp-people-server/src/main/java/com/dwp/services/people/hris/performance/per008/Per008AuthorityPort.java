package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Domain.AuthorityEvidence;
import com.dwp.services.people.hris.performance.per008.Per008Domain.AuthorityRequest;

interface Per008AuthorityPort {
    AuthorityEvidence authorize(AuthorityRequest request);
}
