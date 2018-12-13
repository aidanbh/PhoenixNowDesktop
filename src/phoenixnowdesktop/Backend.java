/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package phoenixnowdesktop;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

/**
 * @author aidanhunt
 */
enum SignInState {
    SIGNED_IN, OFF_CAMPUS, UNKNOWN;
}

class Backend {

    private final String host;
    private final String email;
    private final String pass;

    // responses
    private final String msg_signed_in = "You've checked in today.";
    private final String msg_wrong_ip = "We couldn't check you in. :( Are you on the Guilford network?";

    // the server always operates in EST/EDT
    private final ZoneId server_time = ZoneId.of("America/New_York");

    private CookieStore cookies = new BasicCookieStore();
    private RequestConfig request_config;
    private CloseableHttpClient httpclient;

    private SignInState lastState = SignInState.UNKNOWN;
    private ZonedDateTime lastStateAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(0), server_time);

    // public constructor -- there is no default constructor
    public Backend(String host, String email, String pass) {
        this.host = host;
        this.email = email;
        this.pass = pass;

        request_config = RequestConfig.custom()
                // for fiddler proxy
                .setProxy(new HttpHost("127.0.0.1", 8888, "http"))
                .setCookieSpec(CookieSpecs.STANDARD)
                .build();

        httpclient = HttpClients.custom()
                .disableRedirectHandling() // so we can see the original response codes
                .setDefaultRequestConfig(request_config)
                .setDefaultCookieStore(cookies) // automatically handle session cookies - NB: apparently only remeber_token is checked not session
                .build();

    }

    // main signin code
    // due to the simple design of the server, signing in and 
    // getting the state are one and the same action
    // return if user is signed in or not
    public boolean attemptSignIn() {
        // check if we need to sign in
        // although signins are idempotent, we don't want to change a SIGNED_IN
        // state to an UNKNOWN, which is generated client side (ex. no wifi)

        if (!isSignedIn()) {
            try {
                signIn();
            } catch (Exception e) {
                /*d*/ System.out.println("Caught Exception from signIn()!!!");
                /*d*/ e.printStackTrace();
                // handle network errors and the like
                lastState = SignInState.UNKNOWN;
                this.updateLastStateAt();
            }
        }
        return isSignedIn();

    }

    // returns nothing but updates lastState and lastStateAt
    private void signIn() throws Exception, IllegalStateException {

        // GET /signin endpoint
        URI signin_uri = new URIBuilder()
                .setScheme("http")
                .setHost(host)
                .setPath("/signin")
                .build();
        // HttpGet get_signin = new HttpGet(signin_uri);
        // CloseableHttpResponse response = this.httpclient.execute(get_signin);

        // sign in
        HttpPost post_signin = new HttpPost(signin_uri);
        post_signin.setHeader("Host", this.host);

        // this code from documentation
        List<NameValuePair> urlParams = new ArrayList<>();
        urlParams.add(new BasicNameValuePair("email", this.email));
        urlParams.add(new BasicNameValuePair("password", this.pass));
        urlParams.add(new BasicNameValuePair("submit", "Sign In"));
        post_signin.setEntity(new UrlEncodedFormEntity(urlParams)); // parse response

        /* d */ System.out.println(urlParams.get(0).getValue());
        /* d */ System.out.println(new UrlEncodedFormEntity(urlParams));
        /* d */ System.out.println(post_signin.getEntity());
        CloseableHttpResponse response = this.httpclient.execute(post_signin);

        try {
            switch (response.getStatusLine().getStatusCode()) {
                case 200:

                    // this means we didn't sign in correctly -- bad use of the HTTP codes, but that's how it is
                    // this is going to continue into the next case where we check the response
                    /*d*/ System.out.println("Recieved 200 - invalid signin!");
                    throw new IllegalStateException("Sever rejected login.");
                // login is valid, proceed
                case 302:
                    /*d*/ System.out.println("302");
                    /* if (response.getFirstHeader("Location").getValue().equals(expected_location.toString())) { */
 /* d */ System.out.println(response.getFirstHeader("Location").getValue());
                    break;
                // unexpected 301 Location, 404, 500, etc.
                default:
                    // TODO choose proper exception
                    throw new Exception();
            }
        } finally {
            response.close();
        }

        // now get the mainpage
        URI main_uri = new URIBuilder()
                .setScheme("https")
                .setHost(host)
                .setPath("/")
                .build();
        HttpGet get_main = new HttpGet(main_uri);

        response = this.httpclient.execute(get_main);

        try {
            if (response.getStatusLine().getStatusCode() == 200) {
                String html = EntityUtils.toString(response.getEntity());
                if (html.contains(msg_signed_in)) {
                    this.lastState = SignInState.SIGNED_IN;
                    updateLastStateAt();
                    return; // finally still runs
                } else if (html.contains(msg_wrong_ip)) {
                    this.lastState = SignInState.OFF_CAMPUS;
                    updateLastStateAt();
                    return;
                } else {
                    throw new Exception();
                }
            } else {
                throw new Exception();
            }

        } finally {
            response.close();
        }

    }
    // helper functions
    // check if last response is SIGNED_IN AND is within the same day

    public boolean isSignedIn() {
        if (this.lastState == SignInState.SIGNED_IN
                && lastStateAt.truncatedTo(ChronoUnit.DAYS).isEqual(
                        ZonedDateTime.now(server_time).truncatedTo(ChronoUnit.DAYS)
                )) {
            return (true);
        } else {
            return (false);
        }
    }

    // update lastStateAt to current time
    private void updateLastStateAt() {
        this.lastStateAt = ZonedDateTime.now(this.server_time);
    }

    // autogenerated getters
    public String getHost() {
        return host;
    }

    public SignInState getLastState() {
        return lastState;
    }

    public ZonedDateTime getLastStateAt() {
        return lastStateAt;
    }

}
