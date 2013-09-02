package com.fluxtream.connectors.withings;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import com.fluxtream.Configuration;
import com.fluxtream.auth.AuthHelper;
import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.updaters.UpdateInfo.UpdateType;
import com.fluxtream.domain.ApiKey;
import com.fluxtream.domain.Guest;
import com.fluxtream.services.ConnectorUpdateService;
import com.fluxtream.services.GuestService;
import com.fluxtream.utils.UnexpectedHttpResponseCodeException;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import static com.fluxtream.utils.HttpUtils.fetch;

@Controller()
@RequestMapping("/withings")
public class WithingsConnectorController {

    private static final String WBSAPI_GETUSERSLIST = "http://wbsapi.withings.net/account?action=getuserslist&email=";
    private static final String WBSAPI_ONCE_ACTION_GET = "http://wbsapi.withings.net/once?action=get";

    @Autowired
    Configuration env;

    @Autowired
    GuestService guestService;

    @Qualifier("connectorUpdateServiceImpl")
    @Autowired
    ConnectorUpdateService connectorUpdateService;

    @RequestMapping(value = "/chooseWithingsUser")
    public ModelAndView chooseWithingsUser(HttpServletRequest request) throws UnsupportedEncodingException {
        String publickey = request.getParameter("chosenUser");
        List<UsersListResponseUser> withingsUsers = (List<UsersListResponseUser>) request.getSession()
                .getAttribute("scaleUsers");
        long userid = getUserIdWithPublicKey(
                withingsUsers, publickey);
        long guestId = AuthHelper.getGuestId();
        Guest guest = guestService.getGuestById(Long.valueOf(guestId));
        if (guest == null) {
            ModelAndView mav = new ModelAndView("general-error");
            mav.addObject("errorMessage", "There is no user with specified id: " + guestId);
            return mav;
        } else {
            final Connector connector = Connector.getConnector("withings");
            final ApiKey apiKey = guestService.createApiKey(guest.getId(), connector);

            guestService.setApiKeyAttribute(apiKey, "publickey", publickey);
            guestService.setApiKeyAttribute(apiKey, "userid", String.valueOf(userid));

            connectorUpdateService.scheduleUpdate(apiKey, 3,
                                                  UpdateType.INITIAL_HISTORY_UPDATE,
                                                  System.currentTimeMillis());
        }
        ModelAndView mav = new ModelAndView("connectors/withings/success");
        mav.addObject("guestId", guestId);
        return mav;
    }

    @RequestMapping(value = "/enterCredentials")
    public ModelAndView signin() {
        ModelAndView mav = new ModelAndView("connectors/withings/enterCredentials");
        return mav;
    }

    private long getUserIdWithPublicKey(List<UsersListResponseUser> attribute,
                                        String publickey) {
        for (UsersListResponseUser user : attribute) {
            if (user.getPublickey().equals(publickey))
                return user.getId();
        }
        return -1;
    }

    @RequestMapping(value="/setupWithings")
    public String setupWithings(HttpServletRequest request)
            throws NoSuchAlgorithmException, IOException
    {
        String email = request.getParameter("username");
        String password = request.getParameter("password");
        email = email.trim();
        password = password.trim();
        request.setAttribute("username", email);
        List<String> required = new ArrayList<String>();
        if (email.equals(""))
            required.add("username");
        if (password.equals(""))
            required.add("password");
        if (required.size()!=0) {
            request.setAttribute("required", required);
            return "connectors/withings/enterCredentials";
        }
        try {
            List<UsersListResponseUser> scaleUsers = getScaleUsers(email, password);
            request.getSession(true).setAttribute("scaleUsers", scaleUsers);
            request.setAttribute("scaleUsers", scaleUsers);
        }
        catch (UnexpectedHttpResponseCodeException e) {
            int code = Integer.valueOf(e.getHttpResponseCode());
            switch (code) {
                case 264:
                    request.setAttribute("errorMessage", "The email address provided is either unknown or invalid");
                    break;
                case 2555:
                    request.setAttribute("errorMessage", "An unknown error occurred");
                    break;
                case 100:
                    request.setAttribute("errorMessage", "The hash is missing, invalid, or does not match the provided email");
                    break;
            }
            request.setAttribute("username", email);
            return "connectors/withings/enterCredentials";
        }
        return "connectors/withings/chooseUser";
    }

    public List<UsersListResponseUser> getScaleUsers(String email,
                                                     String password) throws NoSuchAlgorithmException, IOException, UnexpectedHttpResponseCodeException {
        String passwordHash = hash(password);
        String onceJson = fetch(WBSAPI_ONCE_ACTION_GET);
        WithingsOnceResponse once = new Gson().fromJson(onceJson,
                                                        WithingsOnceResponse.class);
        String noonce = once.getBody().getOnce();
        String code = email + ":" + passwordHash + ":" + noonce;
        String hash = hash(code);
        String json = fetch(WBSAPI_GETUSERSLIST + email + "&hash=" + hash);
        System.out.println(json);
        UsersListResponse response = new Gson().fromJson(json,
                                                         UsersListResponse.class);
        if (response.status!=0) throw new RuntimeException(String.valueOf(response.status));
        return response.getBody().getUsers();
    }

    public String hash(String toHash) throws NoSuchAlgorithmException {
        byte[] uniqueKey = toHash.getBytes();
        byte[] hash;
        hash = MessageDigest.getInstance("MD5").digest(uniqueKey);
        StringBuilder hashString = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(hash[i]);
            if (hex.length() == 1) {
                hashString.append('0');
                hashString.append(hex.charAt(hex.length() - 1));
            } else
                hashString.append(hex.substring(hex.length() - 2));
        }
        return hashString.toString();
    }

}
