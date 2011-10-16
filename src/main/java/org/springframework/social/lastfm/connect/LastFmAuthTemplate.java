/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.social.lastfm.connect;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJacksonHttpMessageConverter;
import org.springframework.social.lastfm.api.impl.LastFmApiMethodParameters;
import org.springframework.social.lastfm.auth.AccessGrant;
import org.springframework.social.lastfm.auth.LastFmAuthOperations;
import org.springframework.social.lastfm.auth.LastFmAuthParameters;
import org.springframework.social.support.ClientHttpRequestFactorySelector;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * LastFmAuthOperations implementation that uses REST-template to make the OAuth
 * calls.
 * 
 * @author Michael Lavelle
 */
public class LastFmAuthTemplate implements LastFmAuthOperations {

	private final String clientId;

	private final String clientSecret;

	private final String accessTokenUrl;

	private final String authorizeUrl;

	private final RestTemplate restTemplate;
	
	private final String userAgent;


	public LastFmAuthTemplate(String clientId, String clientSecret,String userAgent) {
		this(clientId, clientSecret, "http://www.last.fm/api/auth/",
				"http://ws.audioscrobbler.com/2.0/",userAgent);
	}

	public LastFmAuthTemplate(String clientId, String clientSecret,
			String authorizeUrl, String accessTokenUrl,String userAgent) {
		Assert.notNull(clientId, "The clientId property cannot be null");
		Assert.notNull(clientSecret, "The clientSecret property cannot be null");
		Assert.notNull(authorizeUrl, "The authorizeUrl property cannot be null");
		Assert.notNull(userAgent, "The userAgent property cannot be null");

		Assert.notNull(accessTokenUrl,
				"The accessTokenUrl property cannot be null");
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		String clientInfo = "?api_key=" + formEncode(clientId);
		this.authorizeUrl = authorizeUrl + clientInfo;
		this.accessTokenUrl = accessTokenUrl;
		this.userAgent = userAgent;
		this.restTemplate = createRestTemplate(true);

	}

	/**
	 * Set the request factory on the underlying RestTemplate. This can be used
	 * to plug in a different HttpClient to do things like configure custom SSL
	 * settings.
	 */
	public void setRequestFactory(ClientHttpRequestFactory requestFactory) {
		Assert.notNull(requestFactory,
				"The requestFactory property cannot be null");
		this.restTemplate.setRequestFactory(requestFactory);
	}

	public String buildAuthorizeUrl(LastFmAuthParameters parameters) {
		return buildAuthUrl(authorizeUrl, parameters);
	}

	public AccessGrant exchangeForAccess(String token,
			MultiValueMap<String, String> additionalParameters) {
		MultiValueMap<String, String> params = new LinkedMultiValueMap<String, String>();
		params.set("api_key", clientId);
		params.set("secret", clientSecret);
		params.set("token", token);
		if (additionalParameters != null) {
			params.putAll(additionalParameters);
		}
		return postForAccessGrant(accessTokenUrl, params);
	}

	// subclassing hooks

	/**
	 * Creates the {@link RestTemplate} used to communicate with the provider's
	 * OAuth 2 API. This implementation creates a RestTemplate with a minimal
	 * set of HTTP message converters ({@link FormHttpMessageConverter} and
	 * {@link MappingJacksonHttpMessageConverter}). May be overridden to
	 * customize how the RestTemplate is created. For example, if the provider
	 * returns data in some format other than JSON for form-encoded, you might
	 * override to register an appropriate message converter.
	 */
	protected RestTemplate createRestTemplate(boolean json) {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.add("User-Agent", userAgent);
		RestTemplate restTemplate = new RestTemplateWithHeaders(
				ClientHttpRequestFactorySelector.getRequestFactory(),httpHeaders);
		List<HttpMessageConverter<?>> converters = new ArrayList<HttpMessageConverter<?>>(
				2);
		converters.add(new FormHttpMessageConverter());
		if (json)
		{
		converters.add(new MappingJacksonHttpMessageConverter());
		}
		restTemplate.setMessageConverters(converters);
		return restTemplate;
	}

	/**
	 * Posts the request for an access grant to the provider. The default
	 * implementation uses RestTemplate to request the access token and expects
	 * a JSON response to be bound to a Map. The information in the Map will be
	 * used to create an {@link AccessGrant}. Since the OAuth 2 specification
	 * indicates that an access token response should be in JSON format, there's
	 * often no need to override this method. If all you need to do is capture
	 * provider-specific data in the response, you should override
	 * createAccessGrant() instead. However, in the event of a provider whose
	 * access token response is non-JSON, you may need to override this method
	 * to request that the response be bound to something other than a Map. For
	 * example, if the access token response is given as form-encoded, this
	 * method should be overridden to call RestTemplate.postForObject() asking
	 * for the response to be bound to a MultiValueMap (whose contents can then
	 * be used to create an AccessGrant).
	 * 
	 * @param accessTokenUrl
	 *            the URL of the provider's access token endpoint.
	 * @param parameters
	 *            the parameters to post to the access token endpoint.
	 * @return the access grant.
	 */
	@SuppressWarnings("unchecked")
	protected AccessGrant postForAccessGrant(String accessTokenUrl,
			MultiValueMap<String, String> parameters) {

		String apiKey = parameters.getFirst("api_key");
		String token = parameters.getFirst("token");
		String secret = parameters.getFirst("secret"); 
		MultiValueMap<String, String> authParams = new LastFmApiMethodParameters(
				"auth.getSession", apiKey, token, secret,new HashMap<String,String>());
		return extractAccessGrant(token, restTemplate.postForObject(
				accessTokenUrl, authParams, Map.class));
	}

	/**
	 * Creates an {@link AccessGrant} given the response from the access token
	 * exchange with the provider. May be overridden to create a custom
	 * AccessGrant that captures provider-specific information from the access
	 * token response.
	 * 
	 * @param accessToken
	 *            the access token value received from the provider
	 * @param scope
	 *            the scope of the access token
	 * @param refreshToken
	 *            a refresh token value received from the provider
	 * @param expiresIn
	 *            the time (in seconds) remaining before the access token
	 *            expires.
	 * @param response
	 *            all parameters from the response received in the access token
	 *            exchange.
	 * @return an {@link AccessGrant}
	 */
	protected AccessGrant createAccessGrant(String token, String sessionKey,
			Map<String, Object> result) {
		return new AccessGrant(token, sessionKey);
	}

	// testing hooks

	protected RestTemplate getRestTemplate() {
		return restTemplate;
	}

	// internal helpers

	private String buildAuthUrl(String baseAuthUrl,
			LastFmAuthParameters parameters) {
		StringBuilder authUrl = new StringBuilder(baseAuthUrl);

		for (Iterator<Entry<String, List<String>>> additionalParams = parameters
				.entrySet().iterator(); additionalParams.hasNext();) {
			Entry<String, List<String>> param = additionalParams.next();
			String name = formEncode(param.getKey());
			for (Iterator<String> values = param.getValue().iterator(); values
					.hasNext();) {
				authUrl.append('&').append(name).append('=')
						.append(formEncode(values.next()));
			}
		}
		return authUrl.toString();
	}

	private String formEncode(String data) {
		try {
			return URLEncoder.encode(data, "UTF-8");
		} catch (UnsupportedEncodingException ex) {
			// should not happen, UTF-8 is always supported
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	private AccessGrant extractAccessGrant(String token,
			Map<String, Object> result) {
		return createAccessGrant(token,
				(String) ((Map<String, Object>) result.get("session"))
						.get("key"), result);

	}

}