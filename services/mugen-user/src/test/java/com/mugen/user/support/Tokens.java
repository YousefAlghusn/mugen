package com.mugen.user.support;

import com.mugen.test.Fixture;
import com.mugen.test.TokenSigner;
import com.mugen.web.security.VerificationKeyProperties;

/** Opts this service into mugen-test's token minting; see {@link TokenSigner}. */
@Fixture
public class Tokens extends TokenSigner {

    public Tokens(VerificationKeyProperties verificationKeyProperties) {
        super(verificationKeyProperties);
    }
}
