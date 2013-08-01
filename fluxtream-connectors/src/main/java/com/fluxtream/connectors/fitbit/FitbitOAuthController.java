package com.fluxtream.connectors.fitbit;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.fluxtream.Configuration;
import com.fluxtream.aspects.FlxLogger;
import com.fluxtream.auth.AuthHelper;
import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.ObjectType;
import com.fluxtream.connectors.SignpostOAuthHelper;
import com.fluxtream.connectors.controllers.ControllerSupport;
import com.fluxtream.domain.ApiKey;
import com.fluxtream.domain.Guest;
import com.fluxtream.services.ApiDataService;
import com.fluxtream.services.GuestService;
import net.sf.json.JSONObject;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.OAuthProvider;
import oauth.signpost.basic.DefaultOAuthConsumer;
import oauth.signpost.basic.DefaultOAuthProvider;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;
import oauth.signpost.exception.OAuthNotAuthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping(value = "/fitbit")
public class FitbitOAuthController {

	FlxLogger logger = FlxLogger.getLogger(FitbitOAuthController.class);

	@Autowired
	GuestService guestService;

	@Autowired
	ApiDataService apiDataService;

	@Autowired
	SignpostOAuthHelper signpostHelper;

	@Autowired
	Configuration env;
	private static final String FITBIT_OAUTH_CONSUMER = "fitbitOAuthConsumer";
	private static final String FITBIT_OAUTH_PROVIDER = "fitbitOAuthProvider";
	public static final String GET_USER_PROFILE_CALL = "FITBIT_GET_USER_PROFILE_CALL";

	static {
		ObjectType.registerCustomObjectType(GET_USER_PROFILE_CALL);
	}

	@RequestMapping(value = "/token")
	public String getToken(HttpServletRequest request) throws IOException, ServletException,
			OAuthMessageSignerException, OAuthNotAuthorizedException,
			OAuthExpectationFailedException, OAuthCommunicationException {

		String oauthCallback = ControllerSupport.getLocationBase(request) + "fitbit/upgradeToken";
		if (request.getParameter("guestId") != null)
			oauthCallback += "?guestId=" + request.getParameter("guestId");

		String consumerKey = env.get("fitbitConsumerKey");
		String consumerSecret = env.get("fitbitConsumerSecret");

		OAuthConsumer consumer = new DefaultOAuthConsumer(consumerKey,
				consumerSecret);

		OAuthProvider provider = new DefaultOAuthProvider(
				"http://api.fitbit.com/oauth/request_token",
				"http://api.fitbit.com/oauth/access_token",
				"http://api.fitbit.com/oauth/authorize");

		request.getSession().setAttribute(FITBIT_OAUTH_CONSUMER, consumer);
		request.getSession().setAttribute(FITBIT_OAUTH_PROVIDER, provider);

		String approvalPageUrl = provider.retrieveRequestToken(consumer,
				oauthCallback);

		return "redirect:" + approvalPageUrl;
	}

	private void fetchUserProfile(ApiKey apiKey, FitbitUserProfile userProfile)
			throws Exception {
		logger.info("guestId=" + apiKey.getGuestId() + " action=fitbit.fetchUserProfile "
				+ "helper=" + signpostHelper + " connector=" + connector()
				+ " guestId=" + apiKey.getGuestId());
		String json = signpostHelper.makeRestCall(apiKey,
				GET_USER_PROFILE_CALL.hashCode(),
				"http://api.fitbit.com/1/user/-/profile.json");

		JSONObject rootJson = JSONObject.fromObject(json);
		JSONObject userJson = rootJson.getJSONObject("user");

		if (userJson.has("aboutMe"))
			userProfile.aboutMe = userJson.getString("aboutMe");
		if (userJson.has("city"))
			userProfile.city = userJson.getString("city");
		if (userJson.has("country"))
			userProfile.country = userJson.getString("country");
		if (userJson.has("dateOfBirth"))
			userProfile.dateOfBirth = userJson.getString("dateOfBirth");
		if (userJson.has("displayName"))
			userProfile.displayName = userJson.getString("displayName");
		if (userJson.has("encodedId"))
			userProfile.encodedId = userJson.getString("encodedId");
		if (userJson.has("fullName"))
			userProfile.fullName = userJson.getString("fullName");
		if (userJson.has("gender"))
			userProfile.gender = userJson.getString("gender");
		if (userJson.has("height"))
			userProfile.height = userJson.getDouble("height");
		if (userJson.has("nickname"))
			userProfile.nickname = userJson.getString("nickname");
		if (userJson.has("offsetFromUTCMillis"))
			userProfile.offsetFromUTCMillis = userJson
					.getLong("offsetFromUTCMillis");
		if (userJson.has("state"))
			userProfile.state = userJson.getString("state");
		if (userJson.has("strideLengthRunning"))
			userProfile.strideLengthRunning = userJson
					.getDouble("strideLengthRunning");
		if (userJson.has("strideLengthWalking"))
			userProfile.strideLengthWalking = userJson
					.getDouble("strideLengthWalking");
		if (userJson.has("timezone"))
			userProfile.timezone = userJson.getString("timezone");
		if (userJson.has("weight"))
			userProfile.weight = userJson.getDouble("weight");
	}

	@RequestMapping(value = "/upgradeToken")
	public String upgradeToken(HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		OAuthConsumer consumer = (OAuthConsumer) request.getSession()
				.getAttribute(FITBIT_OAUTH_CONSUMER);
		OAuthProvider provider = (OAuthProvider) request.getSession()
				.getAttribute(FITBIT_OAUTH_PROVIDER);
		String verifier = request.getParameter("oauth_verifier");
		provider.retrieveAccessToken(consumer, verifier);
		Guest guest = AuthHelper.getGuest();

        final ApiKey apiKey = guestService.createApiKey(guest.getId(), connector());

		guestService.setApiKeyAttribute(apiKey,
				"accessToken", consumer.getToken());
		guestService.setApiKeyAttribute(apiKey,
				"tokenSecret", consumer.getTokenSecret());

		FitbitUserProfile userProfile = new FitbitUserProfile();
		fetchUserProfile(apiKey, userProfile);
		guestService.saveUserProfile(guest.getId(), userProfile);

		return "redirect:/app/from/" + connector().getName();
	}

	private Connector connector() {
		Connector fitbitConnector = Connector.getConnector("fitbit");
		return fitbitConnector;
	}

}
