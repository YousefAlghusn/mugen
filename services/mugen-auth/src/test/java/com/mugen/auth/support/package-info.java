/**
 * mugen-auth's half of the test setup: the containers, the two composed annotations
 * that name them, and the fixtures.
 * <p>
 * Nothing here is a test — the runners are pointed at {@code unit/}, {@code slice/} and
 * {@code integration/}, so this package is never scanned for one. It is the answer to
 * "what do I have to write to add a test", and the answer should stay: one annotation,
 * plus {@link com.mugen.auth.support.AuthFixtures} if the test needs a signed-in user.
 *
 * @see com.mugen.test the tier definitions, shared by every service
 */
package com.mugen.auth.support;
