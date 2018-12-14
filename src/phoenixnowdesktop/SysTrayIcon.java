/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package phoenixnowdesktop;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Font;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.Toolkit.*;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.naming.OperationNotSupportedException;
import java.awt.Image;
import java.awt.image.BufferedImage;

/**
 *
 * @author aidanhunt
 */
public final class SysTrayIcon extends TrayIcon {

    private PopupMenu menu;

    // ref to main window
    private PhoenixNowWindow window;

    private CheckboxMenuItem isSignedInCB;

    // preload a dummy image so we can catch all ioexceptions
    private static final Image dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    // this is called by the "parent" window to register the things
    // to avoid adding major complexity for the edge case of an unsupported tray
    // we still make the SysTrayIcon and return it without adding
    static SysTrayIcon registerSysTrayIcon(PhoenixNowWindow window) {

        SysTrayIcon i = new SysTrayIcon(window);

        //  confirm that tray exists
        if (SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().add(i);
            } catch (AWTException e) {
                System.out.println("Java was not able to add the tray icon.");
            }
        } else {
            System.out.println("Java reports that system tray is not supported here :(");
        }

        // return the new object of this so window can call back
        return i;

    }

    private SysTrayIcon(PhoenixNowWindow window) {
        super(dummy, "PhoenixNowDesktop");

        super.setImageAutoSize(true);

        // make reference to "parent" window
        this.window = window;

        // construct a popup menu
        this.menu = setupPopupMenu();

        // register the menu
        super.setPopupMenu(this.menu);

        // and handle clicks on ourself
        super.setActionCommand("iconclick");
        super.addActionListener(new ActionsListener());

        // get the initial state -- this fixes the icon
        // this.updateSignInState(window.askIsSignedIn());
        // actually, don't do that -- it makes a false notification
        // because window hasn't attempted a signin yet
    }

    private void setImage(String name) {
        try {
            super.setImage(ImageIO.read(new File(name)));
        } catch (IOException e) {
            e.printStackTrace(); // this should never happen
            // if so, it means the files are missing from the build which is a problem
        }
    }

    private PopupMenu setupPopupMenu() {
        PopupMenu pm = new PopupMenu();
        // make things visible on my hidpi display
        pm.setFont(new Font("SansSerif", Font.PLAIN, 18));

        // add application info
        pm.add(new MenuItem("PhoenixNow Desktop"));
        pm.getItem(0).setEnabled(false);

        // checkbox to display signin state
        pm.add(this.isSignedInCB = new CheckboxMenuItem("Signed In?"));
        this.isSignedInCB.setEnabled(false);

        // item to run signin
        pm.add(new MenuItem("Sign In Now..."));
        pm.getItem(2).setActionCommand("signinitemclick");
        pm.getItem(2).addActionListener(new ActionsListener());

        // allow the user to actually exit
        pm.add(new MenuItem("Exit..."));
        pm.getItem(3).setActionCommand("hardexit");
        pm.getItem(3).addActionListener(new ActionsListener());

        return pm;

    }

    void updateSignInState(boolean state) {

        /* d */ System.out.println("The windows tells me: " + state);

        // update the checkbox
        this.isSignedInCB.setState(state);

        // notifications
        // always send the positive notification -- it's the logic in window
        // of disabling the timers on success that prevents constant popups
        if (state) {
            this.displayMessage("PhoenixNow -- Signed In", "You have signed in for today!", MessageType.INFO);
        }

        // and the icon
        if (state) {
            this.setImage("checkmark.png");
        } else {
            this.setImage("phoenixnowlogo.png");
        }
    }

    private class ActionsListener implements ActionListener {

        @Override
        public void actionPerformed(ActionEvent e) {
            switch (e.getActionCommand()) {
                case "signinitemclick":
                    /* d */ System.out.println("Signin Menu Item Clicked...");
                    // let the window handle the signin
                    window.doSignIn(); // which will call back here to update stuff
                    // we only want this if the user initated a signin here (to increase visiblity
                    // otherwise, there will be a lot of obnoxious notifications if the program is running
                    // before the user is on campus for the day
                    if (!window.askIsSignedIn()) {
                        displayMessage("PhoenixNow -- Error", "You could not be signed in. Make sure you are connected to the school network.", MessageType.ERROR);
                    }
                    break;
                case "iconclick":
                    window.showMe();
                    break;
                case "hardexit":
                    System.exit(0);
                    break;
                default:
                    /* d */ System.out.println("Someone's ActionCommand is set wrong...");
            }
        }

    }

}
