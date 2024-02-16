/***************************************************************************
 *   Copyright (C) 2011 by Paul Lutus                                      *
 *   lutusp@arachnoid.com                                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 ***************************************************************************/
package jwx;

import javax.swing.*;
import java.io.IOException;

/**
 *
 * @author lutusp
 */
final public class CommonCode {

    static short mask = 0xff;

    public static boolean ask_user(JFrame src, String query, String title, Object[] options) {
        if (options != null) {
            return (JOptionPane.showOptionDialog(src, query, title,
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]) == 0);
        } else {
            return (JOptionPane.showConfirmDialog(src, query, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
        }

    }

    public static boolean ask_user(JFrame src, String query, String title) {
        return ask_user(src, query, title, null);
    }

    public static void tell_user(JFrame src, String message, String title) {
        JOptionPane.showMessageDialog(src, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    public static byte[] translate_line(byte[] a, int p) {
        int len = a.length;
        p = (len * 8 + p) % len;
        int lp = len - p;
        byte[] x = new byte[len];
        System.arraycopy(a, p, x, 0, lp);
        System.arraycopy(a, 0, x, lp, p);
        return x;
    }

    public static byte[] clock_correct_line(byte[] a, int line, double delta) {
        int len = a.length;
        int p = (int) (delta * line);
        p = (len * 32 + p) % len;
        if (p != 0) {
            int lp = len - p;
            byte[] x = new byte[len];
            System.arraycopy(a, p, x, 0, lp);
            System.arraycopy(a, 0, x, lp, p);
            return x;
        }
        return a;
    }

    public static double[] clock_correct_line(double[] a, int line, double delta) {
        int len = a.length;
        int p = (int) (delta * line);
        p = (len * 32 + p) % len;
        if (p != 0) {
            int lp = len - p;
            double[] x = new double[len];
            System.arraycopy(a, p, x, 0, lp);
            System.arraycopy(a, 0, x, lp, p);
            return x;
        }
        return a;
    }

    public static void launch_browser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (IOException e) {
            p(e);
        }
    }

    // debugging print utility
    public static <T> void p(T s) {
        System.out.println(s);
    }
}
