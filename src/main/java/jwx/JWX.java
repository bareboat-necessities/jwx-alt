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

/*
 * JWX.java
 *
 * Created on Feb 8, 2011, 12:04:53 PM
 */
package jwx;

import javax.sound.sampled.Mixer;
import javax.swing.*;
import java.io.*;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Timer;
import java.util.*;

/**
 *
 * @author lutusp
 */
final public class JWX extends javax.swing.JFrame {

    final boolean debug;
    final String VERSION = "3.0";
    final List<String> data_rates = Arrays.asList(
            "8000", "12000", "16000", "24000", "32000", "48000", "96000");
    final String default_rate = "24000";
    final String default_thresh = "50";
    final String default_volume = "0";
    final int delete_hours = 48;
    final int max_open_charts = 16;
    ConfigManager config_mgr;
    final DecodeFax decode_fax;
    final AudioProcessor audio_processor;
    ToggleButtonController grayscale, afc, fullscale, scroll_to_bottom, filter;
    ComboBoxController data_rate, threshold, monitor_volume, audio_input, audio_output;
    CalibrationController calibration;
    FrameController appsize;
    final String app_path, app_name, program_name, user_dir, data_path, chart_path, init_path, file_sep;
    // the default image width is based on the IOC = Index of Coooperation
    // IOC 576 originates in the old mechanical fax drum diameter, and 576 * pi = 1809.557
    final int default_image_width = 1810;
    final int timer_period_ms = 500;
    long old_samplecount = 0;
    final List<ChartPanel> chart_list;
    int chart_number = 0;
    final Timer periodic_timer;
    ChartPanel current_chart = null;
    HelpPane help_pane = null;
    boolean scaled_images;
    int calibrate_phase = 0;
    double old_mouse_x, old_mouse_y;
    int audio_read = 0;
    double reset_target_time;
    static final long serialVersionUID = 21614;

    /** Creates new form JWX
     * @param args */
    public JWX(String[] args) {
        initComponents();
        debug = (args.length > 0 && args[0].equals("-d"));
        app_name = getClass().getSimpleName();
        URL url = getClass().getResource(app_name + ".class");
        String temp = url.getPath().replaceFirst("(.*?)!.*", "$1");
        temp = temp.replaceFirst("file:", "");
        app_path = new File(temp).getPath();
        user_dir = System.getProperty("user.home");
        file_sep = System.getProperty("file.separator");
        data_path = user_dir + file_sep + "." + app_name;
        chart_path = data_path + file_sep + "charts";
        File f = new File(chart_path);
        if (!f.exists()) {
            f.mkdirs();
        }
        init_path = data_path + file_sep + app_name + ".ini";
        program_name = app_name + " " + VERSION;
        setTitle(program_name);
        setIconImage(new ImageIcon(getClass().getResource("images/" + app_name + "_icon.png")).getImage());
        chart_list = new ArrayList<>();
        audio_processor = new AudioProcessor(this);
        setup_values();
        checkOldCharts();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> inner_close()));
        // these next two must be created in displayed order
        decode_fax = new DecodeFax(this);
        decode_fax.init_chart_read(true);
        scaled_images = !fullscale.get_value();
        periodic_timer = new java.util.Timer();
        periodic_timer.scheduleAtFixedRate(new PeriodicEvents(), 500, timer_period_ms);
    }

    class PeriodicEvents extends TimerTask {

        @Override
        public void run() {
            scaled_images = !fullscale.get_value();
            chart_list.forEach(ChartPanel::perform_periodic);
            set_control_enables();
            decode_fax.periodic_actions();
            set_machine_status();
            set_audio_status();
            set_frequency_status();
            audio_processor.periodic_actions();
            debug_monitor();
            repaint();
        }
    }

    private void debug_monitor() {
        if (debug) {
            double free = Runtime.getRuntime().freeMemory();
            String str = String.format(
                    "time %f reset_target_time %f %s chart %d free mem %.2e\n"
                    + "  ... integ %.2f wsig %.2f image_line %d sample_count %d audio_read %d \n"
                    + "... bi start %.4f active %d bi end %.4f active %d",
                    decode_fax.time_sec,
                    reset_target_time,
                    decode_fax.state.toString(),
                    chart_number,
                    free,
                    decode_fax.pll_integral,
                    decode_fax.wsig,
                    decode_fax.image_line,
                    decode_fax.sample_count,
                    audio_read,
                    decode_fax.gstart.value(),
                    decode_fax.gstart.active() ? 1 : 0,
                    decode_fax.gend.value(),
                    decode_fax.gend.active() ? 1 : 0);
            p(str);
        }
    }

    private void set_frequency_status() {
        double cycles = 0;
        double delta = (decode_fax.sample_count - old_samplecount) / (double) decode_fax.sample_rate;
        if (delta != 0) {
            old_samplecount = decode_fax.sample_count;
            cycles = decode_fax.frequency_meter_cycles / delta;
            decode_fax.frequency_meter_cycles = 0;
        }
        String s = String.format("Frequency %5.0f Hz", cycles);
        frequency_status_label.setText(s);
    }

    private void set_audio_status() {
        StringBuilder vl = new StringBuilder(" Audio: ");
        if (audio_processor.read_valid()) {
            double v = decode_fax.gain_level;
            int iv = (int) (Math.sqrt(v) / 14.0);
            if (v < 1.0) {
                vl.append("None");
            } else if (iv < 1) {
                vl.append("Low");
            } else if (iv > 11) {
                vl.append("*** High ***");
            } else {
                for (int i = 0; i < iv; i++) {
                    vl.append("|");
                }
            }
        } else {
            if (decode_fax.enabled()) {
                vl.append("Error");
            } else {
                vl.append("Standby");
            }
        }
        this.audio_status_label.setText(vl.toString());
    }

    private void set_machine_status() {
        boolean receive = decode_fax.enabled();
        String s = (receive ? "Receive" : "Standby");
        if (receive) {
            s += " | " + decode_fax.state;
        }
        machine_status_label.setText(s);
    }

    private void set_control_enables() {
        boolean receiving_fax = decode_fax.receiving_fax();
        boolean current_chart_receiving = (current_chart != null && current_chart.image_panel.receiving_fax());
        //quit_button.setEnabled(!receiving_fax);
        standby_button.setEnabled(!receiving_fax);
        lock_button.setEnabled(!receiving_fax && decode_fax.enabled());
        unlock_button.setEnabled(receiving_fax);
        receive_button.setEnabled(!decode_fax.enabled());
        standby_button.setEnabled(decode_fax.enabled() && !receiving_fax);
        defaults_button.setEnabled(!decode_fax.enabled());
        calibrate_button.setEnabled(!current_chart_receiving);
        calibration_textfield.setEnabled(!current_chart_receiving && calibrate_phase != 0);
        rate_combobox.setEnabled(!decode_fax.enabled());
        this.audio_output_combobox.setEnabled(audio_processor.write_valid());
        audio_input_combobox.setEnabled(!decode_fax.enabled());
        // update tone threshold
        double thresh = this.threshold.get_percent_value();
        decode_fax.gstart.set_threshold(thresh * decode_fax.g_gain_adjust);
        decode_fax.gend.set_threshold(thresh * decode_fax.g_gain_adjust);
    }

    private List<String> make_numeric_list(int a, int b, int step) {
        List<String> data = new ArrayList<>();
        for (int i = a; i != b; i += step) {
            data.add(Integer.toString(i));
        }
        return data;
    }

    private <T> List<String> make_string_list(List<T> data) {
        List<String> out = new ArrayList<>();
        data.forEach((T item) -> out.add(item.toString()));
        return out;
    }

    private List<String> make_mixer_description_list(List<Mixer.Info> data, String extra) {
        List<String> out = new ArrayList<>();
        if (extra != null) {
            out.add(extra);
        }
        data.forEach((item) -> out.add(item.getDescription()));

        return out;
    }

    private void setup_values() {
        data_rate = new ComboBoxController(rate_combobox, data_rates, default_rate);
        List<String> data = make_numeric_list(1, 251, 1);
        threshold = new ComboBoxController(threshold_combobox, data, default_thresh);
        data = make_numeric_list(0, 501, 1);
        monitor_volume = new ComboBoxController(monitor_volume_combobox, data, default_volume);
        data = make_numeric_list(1, audio_processor.target_mixer_count + 1, 1);
        java.util.List<String> sdata = make_mixer_description_list(audio_processor.target_mixer_list, null);
        audio_input = new ComboBoxController(audio_input_combobox, data, "1", sdata, "Select input");
        data = make_numeric_list(1, audio_processor.source_mixer_count + 1, 1);
        sdata = make_mixer_description_list(audio_processor.source_mixer_list, null);
        audio_output = new ComboBoxController(audio_output_combobox, data, "1", sdata, "Select output");
        grayscale = new ToggleButtonController(grayscale_checkbox, true);
        fullscale = new ToggleButtonController(fullscale_checkbox, false);
        scroll_to_bottom = new ToggleButtonController(scroll_checkbox, true);
        filter = new ToggleButtonController(filter_checkbox, false);

        calibration = new CalibrationController(calibration_textfield, "0", this);
        appsize = new FrameController(this);
        config_mgr = new ConfigManager(this, init_path);
    }

    private void set_control_defaults() {
        if (CommonCode.ask_user(this, "OK to reset all settings to defaults\n(except calibration)?", "Reset Defaults")) {
            data_rate.set_value(default_rate);
            threshold.set_value(default_thresh);
            monitor_volume.set_value(default_volume);
            grayscale.set_value(true);
            fullscale.set_value(false);
            scroll_to_bottom.set_value(true);
            filter.set_value(false);
        }
    }

    public ChartPanel new_chart(String path, double cal_val) {
        ChartPanel panel = new ChartPanel(this, path, cal_val);
        chart_list.add(panel);
        chart_number++;
        tabbed_pane.addTab("chart " + chart_number, panel);
        tabbed_pane.setSelectedComponent(panel);
        int index = tabbed_pane.getSelectedIndex();
        tabbed_pane.setToolTipTextAt(index, path);
        current_chart = panel;
        while (chart_list.size() > max_open_charts) {
            ChartPanel cp = chart_list.remove(0);
            cp.close();
            //cp = null;
        }
        return panel;
    }

    public ChartPanel new_chart(double cal_val) {
        String path = chart_path + file_sep + create_date_time_filename();
        return new_chart(path, cal_val);
    }

    public String create_date_time_filename() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy'_'HH-mm-ss.S");
        String s = sdf.format(date);
        s = s.replaceFirst("(\\.\\d).*", "$1");
        s = s.replaceAll("-", ".");
        return "chart_" + s + ".jpg";
    }

    private void load_image() {
        final JFileChooser fc = new JFileChooser(new File(chart_path));
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new GraphicFileFilter());
        fc.setDialogTitle("Load Chart File");
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] files = fc.getSelectedFiles();
            for (File f : files) {
                new_chart(f.toString(), calibration.get_dvalue());
            }
        }
    }

    public void remove_tab(JComponent comp) {
        tabbed_pane.remove(comp);
        if (comp instanceof ChartPanel) {
            ChartPanel cp = (ChartPanel) comp;
            chart_list.remove(cp);
        }
    }

    public void cancel_calibrate() {
        calibrate_control(-1, 0, 0);
    }

    public void calibrate_control(int source, double x, double y) {
        double cval;
        switch (source) {
            case 0: // button
                if (calibrate_phase == 0) {
                    calibrate_phase = 1;
                } else {
                    calibrate_phase = 0;
                }
                break;
            case 1: // text entry
                if (calibrate_phase == 1) {

                    cval = calibration.get_dvalue();
                    process_cal_result(cval, true);
                    calibrate_phase = 0;
                }
                break;
            case 2: // mouse press
                if (current_chart != null) {
                    if (calibrate_phase == 1) {
                        old_mouse_x = x;
                        old_mouse_y = y;
                        calibrate_phase = 2;
                    } else if (calibrate_phase == 2) {
                        double div = y - old_mouse_y;
                        if (div != 0.0) {
                            cval = (x - old_mouse_x) / (div * current_chart.image_panel.width);
                        } else {
                            cval = 0;
                        }
                        process_cal_result(cval, false);
                        calibrate_phase = 0;
                    }
                }
                break;
            default:
                calibrate_phase = 0;
        }
        calibrate_button.setText((calibrate_phase == 0) ? "Calibrate" : "Cancel");
    }

    private void process_cal_result(double v, boolean entry) {
        if (current_chart != null) {
            if (!entry) {
                double nv = v;
                double ov = calibration.get_dvalue();
                if (CommonCode.ask_user(this, "Okay to save this calibration result?", "Save result")) {
                    if (ov != 0.0) {
                        Object[] options = new Object[]{"Add", "Replace"};
                        if (CommonCode.ask_user(this, "Add to, or replace, existing calibration value?", "Add to or replace value", options)) {
                            nv += ov;
                        }
                    }
                    calibration.set_value(nv);
                }
                if (current_chart != null) {
                    if (CommonCode.ask_user(this, "Okay to apply this calibration to the current chart?", "Apply result")) {
                        current_chart.image_panel.clock_correct(v);
                    }
                }
            }
        }
    }

    private void launch_help() {
        if (help_pane == null) {
            help_pane = new HelpPane(this);
            tabbed_pane.addTab("Help", help_pane);
        }
        tabbed_pane.setSelectedComponent(help_pane);
    }

    private void launch_browser_website() {
        CommonCode.launch_browser("http://arachnoid.com/JWX");
    }

    private void set_current_tab() {
        cancel_calibrate();
        Object obj = this.tabbed_pane.getSelectedComponent();
        if (obj instanceof ChartPanel) {
            current_chart = (ChartPanel) obj;
        } else {
            current_chart = null;
        }
    }

    public String read_file(String fp) {
        Scanner scanner;
        StringBuilder stringBuilder = new StringBuilder();
        try {
            scanner = new Scanner(new File(fp));
            while (scanner.hasNextLine()) {
                stringBuilder.append(scanner.nextLine());
                stringBuilder.append("\n");
            }
            scanner.close();
        } catch (FileNotFoundException ignored) {
        }
        return stringBuilder.toString();
    }

    public void write_file(String fp, String data) {
        try {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(fp))) {
                bw.write(data);
            }
        } catch (IOException e) {
            e.printStackTrace(System.out);
        }
    }

    public void close_inactive_charts(boolean prompt) {
        if (!prompt || CommonCode.ask_user(this, "Okay to close all inactive charts?", "Close all inactive charts")) {
            // copy list to avoid concurrent modification exception
            List<ChartPanel> temp;
            temp = new ArrayList<>();
            chart_list.stream().filter((cp) -> (!cp.image_panel.receiving_fax())).forEachOrdered(temp::add);
            temp.stream().peek(ChartPanel::save_file).forEachOrdered(this::remove_tab);
        }
    }

    private ArrayList<File> getOldChartList() {
        final long delta_t = (long) delete_hours * 60 * 60 * 1000;
        final long now = new Date().getTime();
        File dp = new File(chart_path);
        FilenameFilter ff;
        ff = (File f, String name1) -> name1.matches("^(?i).*\\.jpg$");
        File[] flist = dp.listFiles(ff);
        ArrayList<File> dlist;
        dlist = new ArrayList<>();
        for (File f : flist) {
            long lm = f.lastModified();
            if (lm + delta_t < now) {
                dlist.add(f);
            }
        }
        return dlist;
    }
    
    private void checkOldCharts() {
        ArrayList<File> dlist = getOldChartList();
        delete_button.setEnabled(dlist.size() > 0);
    }
    
    public void delete_old_charts() {
        ArrayList<File> dlist = getOldChartList();
        int n = dlist.size();
        if (n > 0) {
            if (CommonCode.ask_user(this, "Okay to delete " + n + " chart(s) older than " + delete_hours + " hours?", "Delete old charts")) {
                dlist.forEach(File::delete);
            }
        } else {
            CommonCode.tell_user(this, "There are no charts older than " + delete_hours + " hours.", "Delete old charts");
        }
        checkOldCharts();
    }

    private void close() {
        if (!decode_fax.receiving_fax()
                || CommonCode.ask_user(this, "JWX is receiving a chart.\nOkay to save partial chart and quit?", "Receiving Chart")) {
            inner_close();
            System.exit(0);
        }
    }

    // forced close by system
    private void inner_close() {
        decode_fax.init_chart_read(false);
        periodic_timer.cancel();
        close_inactive_charts(false);
        config_mgr.write_config_file();
    }

    public <T> void p(T s) {
        System.out.println(s);
    }

    /*
     * Template to get around the enabled bug:
     *
     * if (evt.getComponent().isEnabled()) { function(); }
     *
     *
     */
    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    //@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        tabbed_pane = new javax.swing.JTabbedPane();
        bottom_panel_a = new javax.swing.JPanel();
        jPanel1 = new javax.swing.JPanel();
        audio_status_label = new javax.swing.JLabel();
        frequency_status_label = new javax.swing.JLabel();
        machine_status_label = new javax.swing.JLabel();
        jLabel1 = new javax.swing.JLabel();
        audio_input_combobox = new javax.swing.JComboBox<>();
        jLabel3 = new javax.swing.JLabel();
        audio_output_combobox = new javax.swing.JComboBox<>();
        jLabel7 = new javax.swing.JLabel();
        monitor_volume_combobox = new javax.swing.JComboBox<>();
        jLabel2 = new javax.swing.JLabel();
        bottom_panel_b = new javax.swing.JPanel();
        filter_checkbox = new javax.swing.JCheckBox();
        calibrate_button = new javax.swing.JButton();
        calibration_textfield = new javax.swing.JTextField();
        receive_button = new javax.swing.JButton();
        standby_button = new javax.swing.JButton();
        lock_button = new javax.swing.JButton();
        unlock_button = new javax.swing.JButton();
        defaults_button = new javax.swing.JButton();
        website_button = new javax.swing.JButton();
        help_button = new javax.swing.JButton();
        bottom_panel_c = new javax.swing.JPanel();
        fullscale_checkbox = new javax.swing.JCheckBox();
        scroll_checkbox = new javax.swing.JCheckBox();
        grayscale_checkbox = new javax.swing.JCheckBox();
        jLabel5 = new javax.swing.JLabel();
        threshold_combobox = new javax.swing.JComboBox<>();
        jLabel4 = new javax.swing.JLabel();
        jLabel6 = new javax.swing.JLabel();
        rate_combobox = new javax.swing.JComboBox<>();
        load_button = new javax.swing.JButton();
        delete_button = new javax.swing.JButton();
        quit_button = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
        setLocationByPlatform(true);
        setMinimumSize(new java.awt.Dimension(400, 400));
        setName("mainframe"); // NOI18N
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });
        getContentPane().setLayout(new java.awt.GridBagLayout());

        tabbed_pane.addChangeListener(evt -> tabbed_paneStateChanged(evt));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        getContentPane().add(tabbed_pane, gridBagConstraints);

        bottom_panel_a.setToolTipText("");
        bottom_panel_a.setLayout(new java.awt.GridBagLayout());

        jPanel1.setLayout(new java.awt.GridLayout(1, 0));

        audio_status_label.setBackground(new java.awt.Color(51, 51, 51));
        audio_status_label.setFont(new java.awt.Font("Monospaced", 1, 12)); // NOI18N
        audio_status_label.setForeground(new java.awt.Color(255, 255, 0));
        audio_status_label.setText("...");
        audio_status_label.setToolTipText("Audio level");
        audio_status_label.setAlignmentX(0.1F);
        audio_status_label.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(255, 255, 0)));
        audio_status_label.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
        audio_status_label.setOpaque(true);
        jPanel1.add(audio_status_label);

        frequency_status_label.setBackground(new java.awt.Color(51, 51, 51));
        frequency_status_label.setFont(new java.awt.Font("Monospaced", 1, 12)); // NOI18N
        frequency_status_label.setForeground(new java.awt.Color(153, 204, 255));
        frequency_status_label.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        frequency_status_label.setText("...");
        frequency_status_label.setToolTipText("Average frequency");
        frequency_status_label.setAlignmentX(0.1F);
        frequency_status_label.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(153, 204, 255)));
        frequency_status_label.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        frequency_status_label.setOpaque(true);
        jPanel1.add(frequency_status_label);

        machine_status_label.setBackground(new java.awt.Color(51, 51, 51));
        machine_status_label.setFont(new java.awt.Font("Monospaced", 1, 12)); // NOI18N
        machine_status_label.setForeground(new java.awt.Color(0, 204, 51));
        machine_status_label.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        machine_status_label.setText("...");
        machine_status_label.setToolTipText("State machine status");
        machine_status_label.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(0, 204, 51)));
        machine_status_label.setOpaque(true);
        jPanel1.add(machine_status_label);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        bottom_panel_a.add(jPanel1, gridBagConstraints);

        jLabel1.setText("In:");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.insets = new java.awt.Insets(0, 2, 0, 2);
        bottom_panel_a.add(jLabel1, gridBagConstraints);

        audio_input_combobox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        audio_input_combobox.setToolTipText("<html>Select audio input channel<br/>\n(while in standby)</html>\n");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 0.5;
        bottom_panel_a.add(audio_input_combobox, gridBagConstraints);

        jLabel3.setText("Out:");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.insets = new java.awt.Insets(0, 2, 0, 2);
        bottom_panel_a.add(jLabel3, gridBagConstraints);

        audio_output_combobox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        audio_output_combobox.setToolTipText("<html>Select audio output channel<br/>\n(0 = disable)</html>");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 0.5;
        bottom_panel_a.add(audio_output_combobox, gridBagConstraints);

        jLabel7.setText("Vol:");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.insets = new java.awt.Insets(0, 2, 0, 2);
        bottom_panel_a.add(jLabel7, gridBagConstraints);

        monitor_volume_combobox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        monitor_volume_combobox.setToolTipText("<html>Adjust output volume (0 = off)<br/>\n(Hold down the shift key to<br/>\nincrease the rate of change)</html>\n");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 0.5;
        bottom_panel_a.add(monitor_volume_combobox, gridBagConstraints);

        jLabel2.setText("%");
        bottom_panel_a.add(jLabel2, new java.awt.GridBagConstraints());

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        getContentPane().add(bottom_panel_a, gridBagConstraints);

        bottom_panel_b.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 2, 2));

        filter_checkbox.setText("Filter");
        filter_checkbox.setToolTipText("Enable image noise filtering");
        filter_checkbox.setMargin(new java.awt.Insets(0, 0, 0, 0));
        bottom_panel_b.add(filter_checkbox);

        calibrate_button.setText("Calibrate");
        calibrate_button.setToolTipText("Start calibration procedure");
        calibrate_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        calibrate_button.setMaximumSize(new java.awt.Dimension(80, 25));
        calibrate_button.setMinimumSize(new java.awt.Dimension(80, 25));
        calibrate_button.setPreferredSize(new java.awt.Dimension(80, 25));
        calibrate_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                calibrate_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(calibrate_button);

        calibration_textfield.setColumns(8);
        calibration_textfield.setHorizontalAlignment(javax.swing.JTextField.RIGHT);
        calibration_textfield.setText("0.000");
        calibration_textfield.setToolTipText("Present calibration value");
        calibration_textfield.setMargin(new java.awt.Insets(4, 2, 4, 2));
        bottom_panel_b.add(calibration_textfield);

        receive_button.setText("Receive");
        receive_button.setToolTipText("Enable receive mode");
        receive_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        receive_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                receive_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(receive_button);

        standby_button.setText("Standby");
        standby_button.setToolTipText("Disable receive mode");
        standby_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        standby_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                standby_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(standby_button);

        lock_button.setText("Lock");
        lock_button.setToolTipText("Force receiver lock without synchronization");
        lock_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        lock_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                lock_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(lock_button);

        unlock_button.setText("Unlock");
        unlock_button.setToolTipText("Unlock receiver, stop reception");
        unlock_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        unlock_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                unlock_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(unlock_button);

        defaults_button.setText("Defaults");
        defaults_button.setToolTipText("Set all default values except calibration");
        defaults_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        defaults_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                defaults_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(defaults_button);

        website_button.setText("Website");
        website_button.setToolTipText("Visit the JWX home page");
        website_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        website_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                website_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(website_button);

        help_button.setText("Help");
        help_button.setToolTipText("Read JWX help");
        help_button.setIconTextGap(0);
        help_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        help_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                help_buttonMouseClicked(evt);
            }
        });
        bottom_panel_b.add(help_button);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        getContentPane().add(bottom_panel_b, gridBagConstraints);

        bottom_panel_c.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 2, 2));

        fullscale_checkbox.setText("Full");
        fullscale_checkbox.setToolTipText("Show images at full scale");
        fullscale_checkbox.setMargin(new java.awt.Insets(0, 0, 0, 0));
        bottom_panel_c.add(fullscale_checkbox);

        scroll_checkbox.setSelected(true);
        scroll_checkbox.setText("Scroll");
        scroll_checkbox.setToolTipText("Scroll to image bottom as data is received");
        scroll_checkbox.setMargin(new java.awt.Insets(0, 0, 0, 0));
        bottom_panel_c.add(scroll_checkbox);

        grayscale_checkbox.setSelected(true);
        grayscale_checkbox.setText("Grayscale |");
        grayscale_checkbox.setToolTipText("Grayscale mode for satellite images and pictures");
        grayscale_checkbox.setMargin(new java.awt.Insets(0, 0, 0, 0));
        bottom_panel_c.add(grayscale_checkbox);

        jLabel5.setText("Threshold: ");
        bottom_panel_c.add(jLabel5);

        threshold_combobox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        threshold_combobox.setToolTipText("<html>Set start/stop tone sensitivity<br/>\n(if you don't know what this is, set it to 30%)<br/>\n(Hold down the shift key to increase the rate of change)</html>\n");
        bottom_panel_c.add(threshold_combobox);

        jLabel4.setText("% |");
        bottom_panel_c.add(jLabel4);

        jLabel6.setText("Rate: ");
        bottom_panel_c.add(jLabel6);

        rate_combobox.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        rate_combobox.setToolTipText("Set data rate (while in standby)");
        bottom_panel_c.add(rate_combobox);

        load_button.setText("Load");
        load_button.setToolTipText("Load a previously received chart file");
        load_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        load_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                load_buttonMouseClicked(evt);
            }
        });
        bottom_panel_c.add(load_button);

        delete_button.setText("Delete...");
        delete_button.setToolTipText("Delete old charts");
        delete_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        delete_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                delete_buttonMouseClicked(evt);
            }
        });
        bottom_panel_c.add(delete_button);

        quit_button.setText("Quit");
        quit_button.setToolTipText("Exit JWX");
        quit_button.setMargin(new java.awt.Insets(2, 2, 2, 2));
        quit_button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                quit_buttonMouseClicked(evt);
            }
        });
        bottom_panel_c.add(quit_button);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        getContentPane().add(bottom_panel_c, gridBagConstraints);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void quit_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_quit_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            close();
        }
    }//GEN-LAST:event_quit_buttonMouseClicked

    private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        close();
    }//GEN-LAST:event_formWindowClosing

    private void defaults_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_defaults_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            set_control_defaults();
        }
    }//GEN-LAST:event_defaults_buttonMouseClicked

    private void unlock_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_unlock_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            decode_fax.unlock();
        }
    }//GEN-LAST:event_unlock_buttonMouseClicked

    private void load_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_load_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            load_image();
        }
    }//GEN-LAST:event_load_buttonMouseClicked

    private void receive_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_receive_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            decode_fax.init_chart_read(true);
        }
    }//GEN-LAST:event_receive_buttonMouseClicked

    private void standby_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_standby_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            decode_fax.init_chart_read(false);
        }
    }//GEN-LAST:event_standby_buttonMouseClicked

    private void lock_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_lock_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            decode_fax.lock();
        }
    }//GEN-LAST:event_lock_buttonMouseClicked

    private void calibrate_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_calibrate_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            calibrate_control(0, 0, 0);
        }
    }//GEN-LAST:event_calibrate_buttonMouseClicked

    private void help_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_help_buttonMouseClicked
        if (evt.getComponent().isEnabled()) {
            launch_help();
        }
    }//GEN-LAST:event_help_buttonMouseClicked

    private void website_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_website_buttonMouseClicked
        launch_browser_website();
    }//GEN-LAST:event_website_buttonMouseClicked

    private void tabbed_paneStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_tabbed_paneStateChanged
        set_current_tab();
    }//GEN-LAST:event_tabbed_paneStateChanged

    private void delete_buttonMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_delete_buttonMouseClicked
        // TODO add your handling code here:
        delete_old_charts();
    }//GEN-LAST:event_delete_buttonMouseClicked

    /**
     * @param args the command line arguments
     */
    public static void main(final String[] args) {
        try {
            // Default to system-specific L&F
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

            java.awt.EventQueue.invokeLater(() -> new JWX(args).setVisible(true));
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | UnsupportedLookAndFeelException e) {
            System.out.println("main: " + e);
        }
    }
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JComboBox<String> audio_input_combobox;
    private javax.swing.JComboBox<String> audio_output_combobox;
    private javax.swing.JLabel audio_status_label;
    private javax.swing.JPanel bottom_panel_a;
    private javax.swing.JPanel bottom_panel_b;
    private javax.swing.JPanel bottom_panel_c;
    private javax.swing.JButton calibrate_button;
    private javax.swing.JTextField calibration_textfield;
    private javax.swing.JButton defaults_button;
    private javax.swing.JButton delete_button;
    private javax.swing.JCheckBox filter_checkbox;
    private javax.swing.JLabel frequency_status_label;
    private javax.swing.JCheckBox fullscale_checkbox;
    private javax.swing.JCheckBox grayscale_checkbox;
    private javax.swing.JButton help_button;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JButton load_button;
    private javax.swing.JButton lock_button;
    private javax.swing.JLabel machine_status_label;
    private javax.swing.JComboBox<String> monitor_volume_combobox;
    private javax.swing.JButton quit_button;
    private javax.swing.JComboBox<String> rate_combobox;
    private javax.swing.JButton receive_button;
    private javax.swing.JCheckBox scroll_checkbox;
    private javax.swing.JButton standby_button;
    private javax.swing.JTabbedPane tabbed_pane;
    private javax.swing.JComboBox<String> threshold_combobox;
    private javax.swing.JButton unlock_button;
    private javax.swing.JButton website_button;
    // End of variables declaration//GEN-END:variables
}
