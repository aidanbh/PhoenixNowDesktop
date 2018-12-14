/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package phoenixnowdesktop;

import com.gargoylesoftware.htmlunit.CookieManager;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.ProxyConfig;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlPasswordInput;
import com.gargoylesoftware.htmlunit.html.HtmlSubmitInput;
import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import java.io.IOException;

/**
 * @author aidanhunt
 */

enum SignInState {
    SIGNED_IN, OFF_CAMPUS, UNKNOWN;
}

class BackendHtmlUnit {

    private final String host;
    private final String email;
    private final String pass;

    // responses
    private final String msg_signed_in = "You've checked in today.";
    private final String msg_wrong_ip = "We couldn't check you in. :( Are you on the Guilford network?";

    // the server always operates in EST/EDT
    private final ZoneId server_time = ZoneId.of("America/New_York");

    private SignInState lastState = SignInState.UNKNOWN;
    private ZonedDateTime lastStateAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(0), server_time);

    // store cookies to make things faster
    CookieManager cookie_jar = new CookieManager();

    // public constructor -- there is no default constructor
    public BackendHtmlUnit(String host, String email, String pass) {
        this.host = host;
        this.email = email;
        this.pass = pass;
    }

    // main signin code
    // due to the simple design of the server, signing in and 
    // getting the state are one and the same action
    // return if user is signed in or not
    public boolean attemptSignIn() {
        // check if we need to sign in
        // although signins are idempotent, we don't want to change a SIGNED_IN
        // state to an UNKNOWN, which is generated client side (ex. no wifi)

        if (!isSignedIn()) { // do not "lose" a valid signin!
            try {
                signIn();
            } catch (Exception e) {
                /*d*/ System.out.println("Caught Exception from signIn()!!!");
                /*d*/ e.printStackTrace();
                // handle network errors and the like
                this.updateLastState(SignInState.UNKNOWN);
            }
        }
        return isSignedIn();

    }

    // returns nothing but updates lastState and lastStateAt
    private void signIn() throws IllegalStateException {

        try (final WebClient webClient = new WebClient();) {

            // store cookies (this is HtmlUnit not Java SE class)
            webClient.setCookieManager(cookie_jar);

            // turn off stuff we don't need here that adds to error surface
            webClient.getOptions().setTimeout(60000);
            webClient.getOptions().setDownloadImages(false);
            webClient.getOptions().setJavaScriptEnabled(false);
            webClient.getOptions().setCssEnabled(false);
            webClient.getOptions().setRedirectEnabled(true);
//            webClient.getOptions().setUseInsecureSSL(true); // for Fiddler
//            webClient.getOptions().setProxyConfig( // also for Fiddler 
//                    new ProxyConfig("localhost", 8888));

            // create an object to store the mainpage for later
            final String main_page_text;

            // GET the signin page
            final HtmlPage signin_page = webClient.getPage("https://".concat(this.host).concat("/signin"));
            // validate that we are on the correct page
            if (!signin_page.getTitleText().equals("PhoenixNow")) {
                // probably behind a captive portal or similar
                throw new IllegalStateException("Server returned invalid signin page: title was " + signin_page.getTitleText());
            } else if (signin_page.asText().contains("Signin")) { // check if we were NOT redirected
                // do the signin
                /* d */ System.out.println("Logging in...");

                // extract the form (there is only one form, and it doesn't have a name)
                final HtmlForm signin_form = signin_page.getForms().get(0);

                // load the elements
                final HtmlTextInput signin_form_email = signin_form.getInputByName("email");
                final HtmlPasswordInput signin_form_password = signin_form.getInputByName("password");
                final HtmlSubmitInput signin_form_submit = signin_form.getInputByName("submit");

                // fill the form (we don't need to worry about using type() as there is no JS at play
                signin_form_email.setText(this.email);
                signin_form_password.setText(this.pass);

                // submit the form and route output to variable for last step
                main_page_text = ((HtmlPage) signin_form_submit.click()).asText();

            } else { // we are on the mainpage page via redirect (I hope...)
                /* d */ System.out.println("Already logged in in!");
                main_page_text = signin_page.asText();
            }

            // now, let's see what the server says
            if (main_page_text.contains(msg_signed_in)) { // the element isn't named so we have to do a contains()
                // yes!! we are signed in!
                this.updateLastState(SignInState.SIGNED_IN);
                return;
            } else if (main_page_text.contains(msg_wrong_ip)) {
                // NB - the server is internally preventing us from "losing" a signin
                // so we don't have to worry about that here
                System.out.println("Not on campus...");
                this.updateLastState(SignInState.OFF_CAMPUS);
            } else {
                // we aren't looking at a valid mainpage
                /* d */ System.out.println(main_page_text);
                throw new IllegalStateException("Server did not return a recognizable status message.");
            }

            // generic exception handlers follow
        } catch (FailingHttpStatusCodeException e) { // 404, 500, etc.
            throw new IllegalStateException(("Server could not process the request: " + e.getStatusMessage()));
        } catch (IOException e) { // represents network issues
            throw new IllegalStateException(("IOException while connecting to server: " + e.toString()));
        } catch (ElementNotFoundException e) { // thrown by signin_form.getInputByName()
            throw new IllegalStateException(("Server returned invalid signin form, missing element: " + e.getElementName()));
        } catch (Exception e) { // handle unexpected exceptions
            throw new IllegalStateException(("Unexpected error occoured: " + e.getMessage()));
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
    private void updateLastState(SignInState state) {
        this.lastState = state;
        this.lastStateAt = ZonedDateTime.now(this.server_time);
    }

    // autogenerated getters
    public String getHost() {
        return host;
    }

    // we don't technically need these b/c everythign is encapsulated by isSignedIn()
    // but it is useful to be able to return this info to the user so they can take action
    SignInState getLastState() {
        return lastState;
    }

    ZonedDateTime getLastStateAt() {
        return lastStateAt;
    }
    
    

}
