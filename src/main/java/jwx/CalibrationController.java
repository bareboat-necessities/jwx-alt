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

/**
 *
 * @author lutusp
 */
final public class CalibrationController implements ControlInterface {

    JWX parent;
    JTextField field;
    private String value;
    private double dvalue = 0;

    public CalibrationController(JTextField f, String s, JWX p) {
        parent = p;
        field = f;
        field.setText(s);
        input_changed();
        field.addKeyListener(new java.awt.event.KeyAdapter() {

            @Override
            public void keyTyped(java.awt.event.KeyEvent evt) {
                test_for_enter(evt);
            }
        });
    }

    private void test_for_enter(java.awt.event.KeyEvent evt) {
        if (evt.getKeyChar() == java.awt.event.KeyEvent.VK_ENTER) {
            input_changed();
        }
    }

    private void input_changed() {
        try {
            value = field.getText();
            dvalue = Double.parseDouble(value);
            process_entry();
        } catch (NumberFormatException e) {
            dvalue = 0.0;
            process_entry();
        }
    }

    private void process_entry() {
        value = String.format("%.4e", dvalue);
        field.setText(value);
        parent.calibrate_control(1, 0, 0);
    }

    public String get_value() {
        return value;
    }

    public double get_dvalue() {
        return dvalue;
    }

    /**
     *
     * @param s
     */
    @Override
    public void set_value(String s) {
        field.setText(s);
        input_changed();
    }

    public void set_value(double v) {
        set_value("" + v);
    }

    @Override
    public String toString() {
        return value;
    }
}
