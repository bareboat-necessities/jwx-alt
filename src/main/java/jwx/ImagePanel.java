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

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
import javax.swing.*;

/**
 *
 * @author lutusp
 */
public class ImagePanel extends javax.swing.JPanel {

    ChartPanel parent;
    BufferedImage buffered_image = null;
    java.util.List<byte[]> image_array;
    int height;
    int width;
    byte[] bbuffer = null;
    int bbuffer_height = 0;
    int block_size = 400;
    int old_line = 0;
    int imagew, imageh;
    int scaledw, scaledh;
    File file;
    boolean receiving_fax;
    double image_scale = 1.0;
    int mousex, mousey;
    int translate_val = 0;
    boolean changed = false;
    static final long serialVersionUID = 22045;

    /** Creates new form ImagePanel
     * @param p
     * @param path */
    public ImagePanel(ChartPanel p, String path) {
        parent = p;
        width = parent.parent.default_image_width;
        initComponents();
        image_array = new java.util.ArrayList<>();
        old_line = 0;
        file = new File(path);
        process_path();
    }

    private void process_path() {
        try {
            receiving_fax = !file.exists();
            if (receiving_fax) {
                file.createNewFile();
            } else {
                load_file();
            }
        } catch (IOException e) {
            CommonCode.p("process_path: " + e);
        }
    }

    private void load_file() {
        try {
            buffered_image = ImageIO.read(file);
            width = buffered_image.getWidth();
            height = buffered_image.getHeight();
            load_data_array();
        } catch (IOException e) {
            CommonCode.p("load file: " + e);
        }
    }

    private void load_data_array() {
        image_array.clear();
        Raster raster = buffered_image.getData();
        DataBufferByte dbb = (DataBufferByte) raster.getDataBuffer();
        byte[][] bb = dbb.getBankData();
        int len = bb[0].length;
        int j = 0;
        byte[] line = new byte[width];
        for (int i = 0; i < len; i++) {
            line[j++] = bb[0][i];
            if (j >= width) {
                image_array.add(line);
                j = 0;
                line = new byte[width];
            }
        }
        update_image(true);
        changed = false;
    }

    public boolean get_changed() {
        return changed;
    }

    public boolean receiving_fax() {
        return receiving_fax;
    }

    public void save_file() {
        if (changed && buffered_image != null && buffered_image.getHeight() > 0) {
            try {
                ImageIO.write(buffered_image, "jpg", file);
                changed = false;
            } catch (IOException e) {
                CommonCode.p("save file: " + e);
            }
        }
    }

    public void update_image(boolean erase) {
        height = image_array.size();
        if (erase) {
            bbuffer = null;
        }
        if (bbuffer == null) {
            bbuffer_height = 0;
            old_line = 0;
        }
        if (height > 0 && height > old_line) {
            // if bbuffer has become too small
            if (height >= bbuffer_height) {
                while (height >= bbuffer_height) {
                    bbuffer_height += block_size;
                }
                // create new byte buffer
                byte[] new_bbuffer = new byte[bbuffer_height * width];
                // copy old contents if they exist
                if (bbuffer != null) {
                    System.arraycopy(bbuffer, 0, new_bbuffer, 0, bbuffer.length);
                }
                bbuffer = new_bbuffer;
            }
            int pos = width * old_line;
            for (int i = old_line; i < height; i++) {
                byte[] line = image_array.get(i);
                System.arraycopy(line, 0, bbuffer, pos, width);
                pos += width;
            }
            old_line = height;
            buffered_image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            DataBuffer dbb = new DataBufferByte(bbuffer, width * height);
            SampleModel sm = buffered_image.getSampleModel();
            Raster r = Raster.createRaster(sm, dbb, null);
            buffered_image.setData(r);
            set_image_scale();
            changed = true;
        }
        repaint();
    }

    public void add_line(byte[] line) {
        if (translate_val != 0) {
            line = CommonCode.translate_line(line, translate_val);
        }
        if (parent.calibration_val != 0.0) {
            int y = image_array.size();
            line = CommonCode.clock_correct_line(line, y, width * parent.calibration_val);
        }
        image_array.add(line);
        changed = true;
    }

    public void scroll_test(boolean force) {
        if ((force || receiving_fax) && parent.parent.scroll_to_bottom.get_value()) {
            SwingUtilities.invokeLater(() -> {
                JScrollBar vbar = parent.get_vert_scroll_bar();
                vbar.setValue(vbar.getMaximum());
            });
        }
    }

    public void set_image_scale() {
        if (buffered_image != null) {
            imagew = buffered_image.getWidth(this);
            imageh = buffered_image.getHeight(this);
            scaledw = imagew;
            scaledh = imageh;
            image_scale = 1.0;
            if (parent.parent.scaled_images) {
                Dimension vs = parent.get_view_size();
                // scale to viewport width
                int dw = vs.width - 1;
                if (dw == 0) {
                    dw = width;
                }
                image_scale = (double) dw / imagew;
                scaledw = (int) (imagew * image_scale);
                scaledh = (int) (imageh * image_scale);
            }
            setPreferredSize(new Dimension(scaledw, scaledh));
            setSize(scaledw, scaledh);
        }
    }

    private boolean mouse_in_image() {
        return (mousex <= imagew && mousey <= imageh);
    }

    private void mouse_exit() {
            parent.set_data_label("");
    }

    private void track_mouse(java.awt.event.MouseEvent evt) {
        int mx = evt.getX();
        int my = evt.getY();
        if (image_scale != 0) {
            mx /= image_scale;
            my /= image_scale;
        }
        mousex = mx;
        mousey = my;
        if (mouse_in_image()) {
            String s = String.format("Mouse: {%d,%d}", mousex, mousey);
            parent.set_data_label(s);
        } else {
            mouse_exit();
        }
    }

    private void manage_mouse_press(java.awt.event.MouseEvent evt) {
        int button = evt.getButton();
        switch (button) {
            case java.awt.event.MouseEvent.BUTTON1:
                translate();
                break;
            case java.awt.event.MouseEvent.BUTTON3:
                if (!receiving_fax) {
                    if (parent.parent.calibrate_phase == 0) {
                        if (CommonCode.ask_user(parent.parent, "Okay to enter calibrate mode?", "Not in calibrate mode")) {
                            parent.parent.calibrate_control(0, 0, 0);
                        } else {
                            return;
                        }
                    }
                    parent.parent.calibrate_control(2, mousex, mousey);
                } else {
                    CommonCode.tell_user(parent.parent, "Cannot calibrate while receiving fax", "Calibration unavailable");
                }
                break;
        }

    }

    private java.util.List<byte[]> translate_image(java.util.List<byte[]> src, int delta) {
        java.util.List<byte[]> b = new java.util.ArrayList<>();
        src.forEach((a) -> {
            b.add(CommonCode.translate_line(a, delta));
        });
        return b;
    }

    private void translate() {
        if (mouse_in_image() && CommonCode.ask_user(parent.parent, "Okay to realign image?", "Adjust Image Alignment")) {
            int delta = mousex;
            translate_val = (translate_val + delta + width * 8) % width;
            image_array = translate_image(image_array, delta);
            update_image(true);
        }
    }

    public void clock_correct(double delta) {
        if (!receiving_fax) {
            delta *= width;
            java.util.List<byte[]> b = new java.util.ArrayList<>();
            int line = 0;
            for (byte[] a : image_array) {
                b.add(CommonCode.clock_correct_line(a, line, delta));
                line++;
            }
            image_array = b;
            update_image(true);
        }
    }

    /**
     *
     */
    public void invert_image() {
        image_array.forEach((b) -> {
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) (255 - b[i]);
            }
        });
        update_image(true);
    }

    public void rotate_image(boolean cw) {
        if (image_array != null) {

            java.util.List<byte[]> dest;
            dest = new java.util.ArrayList<>();
            if (cw) {
                for (int y = 0; y < width; y++) {
                    byte[] row = new byte[height];
                    for (int x = 0; x < height; x++) {
                        row[x] = image_array.get(height - x - 1)[y];
                    }
                    dest.add(row);
                }
            } else { // ccw
                for (int y = 0; y < width; y++) {
                    byte[] row = new byte[height];
                    for (int x = 0; x < height; x++) {
                        row[x] = image_array.get(x)[width - y - 1];
                    }
                    dest.add(row);
                }
            }
            image_array = dest;
            int temp = height;
            height = width;
            width = temp;
            update_image(true);
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        if (buffered_image != null) {
            Graphics2D g2 = (Graphics2D) g;
            if (parent.parent.scaled_images) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.drawImage(buffered_image, 0, 0, scaledw, scaledh, this);
            } else {
                g2.drawImage(buffered_image, 0, 0, this);
            }
        }
    }

    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    //@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                formMouseClicked(evt);
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                formMouseExited(evt);
            }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            public void mouseMoved(java.awt.event.MouseEvent evt) {
                formMouseMoved(evt);
            }
        });
        addHierarchyBoundsListener(new java.awt.event.HierarchyBoundsListener() {
            public void ancestorMoved(java.awt.event.HierarchyEvent evt) {
            }
            public void ancestorResized(java.awt.event.HierarchyEvent evt) {
                formAncestorResized(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 247, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 65, Short.MAX_VALUE)
        );
    }// </editor-fold>//GEN-END:initComponents

    private void formAncestorResized(java.awt.event.HierarchyEvent evt) {//GEN-FIRST:event_formAncestorResized
        // TODO add your handling code here:
        set_image_scale();
    }//GEN-LAST:event_formAncestorResized

    private void formMouseMoved(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_formMouseMoved
        // TODO add your handling code here:
        track_mouse(evt);
    }//GEN-LAST:event_formMouseMoved

    private void formMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_formMouseClicked
        // TODO add your handling code here:
        manage_mouse_press(evt);
    }//GEN-LAST:event_formMouseClicked

    private void formMouseExited(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_formMouseExited
        mouse_exit();
    }//GEN-LAST:event_formMouseExited
    // Variables declaration - do not modify//GEN-BEGIN:variables
    // End of variables declaration//GEN-END:variables
}
