package com.agrosense.backend.security;

import com.agrosense.backend.controller.EventStreamController;
import com.agrosense.backend.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The event routes when their secrets are missing or wrong: closed, never open. */
class EventSecurityTests {

	@Test
	void withoutAConfiguredKeyTheInternalRoutesLetNobodyIn() throws Exception {
		for (String key : new String[] {"", "  ", null}) {
			MockHttpServletResponse response = filter(key, "/api/internal/events", "");
			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getContentAsString()).contains("llave interna");
		}
	}

	@Test
	void theKeyIsCheckedOnlyOnTheInternalRoutes() throws Exception {
		assertThat(filter("k", "/api/internal/events", "k").getStatus()).isEqualTo(200);
		assertThat(filter("k", "/api/internal/events", "k2").getStatus()).isEqualTo(401);
		assertThat(filter("k", "/api/dashboard", null).getStatus()).isEqualTo(200);
	}

	@Test
	void withoutAStreamSecretThereAreNoTokensAndTheRouteSaysSo() {
		EventStreamTokens tokens = new EventStreamTokens("");

		assertThat(tokens.issue("demo@agrosense.co")).isEmpty();
		assertThatThrownBy(() -> new EventStreamController(tokens).token(() -> "demo@agrosense.co"))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@Test
	void aStreamSecretTooShortToSignSafelyStopsTheStart() {
		assertThatThrownBy(() -> new EventStreamTokens("corto")).isInstanceOf(IllegalStateException.class);
	}

	private static MockHttpServletResponse filter(String configured, String path, String sent) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		if (sent != null) {
			request.addHeader(InternalKeyFilter.HEADER, sent);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		new InternalKeyFilter(configured).doFilter(request, response, new MockFilterChain());
		return response;
	}
}
