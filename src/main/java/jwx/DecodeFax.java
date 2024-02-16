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

/**
 *
 * @author lutusp
 */
final public class DecodeFax {

    JWX parent;
    double goertzel_accept;
    GoertzelFilter gstart = null;
    GoertzelFilter gend = null;
    ChartPanel chart_panel;

    // machine state enumeration
    enum State {

        WAITSIG,
        WAITSTB,
        WAITSTE,
        WAITLS1,
        SYNC,
        WAITLS2,
        PROC,
        END
    }

    MachineState[] machine_states = {
        new s_waitsig(),
        new s_waitstb(),
        new s_waitste(),
        new s_waitls1(),
        new s_sync(),
        new s_waitls2(),
        new s_proc(),
        new s_end()
    };
    State state;
    double calibration_val;
    long sample_rate;
    int sync_time;
    double sync_interval;
    int sync_lines;
    int lines_per_second;
    double sample_increm;
    int row_len;
    long long_row_len;
    double sig_sum, sig_count;
    double sampleinterval;
    double gain_thresh;
    double gain_level;
    double gain_tc;
    double cf;
    double invsqr2 = 1.0 / Math.sqrt(2);
    double rcvr_mark, rcvr_space;
    double rcvr_dev, ms_q, bp_q;
    double startf;
    double stopf;
    double wsig;
    double sig;
    double g_size;
    double g_gain_adjust = 0.5;
    double time_sec;
    long sample_count;
    long line_time_zero_count;
    int sign;
    long line_time_delta;
    boolean linetime_zero;
    int frequency_meter_cycles;
    int old_sign;
    int image_line;
    int row_index;
    int line_index;
    double row_pos;
    int timer_counter = 0;
    double[] sync_array;
    double[] sync_line;
    double val;
    byte[] line_buf;
    boolean grayscale = true;
    boolean video_filter = false;
    boolean enabled = false;
    double pll_integral = 0;
    double pll_reference = 0;
    double pll_loop_control;
    double pll_loop_gain = 1;
    double pll_center_f = 1900.0;
    double pll_deviation_f = 400.0;
    // produce a unit output level at maximum deviation
    // plus 20% to improve appearance of charts
    double pll_output_gain = 1.2 * pll_center_f / pll_deviation_f;
    double pll_omega = 2 * Math.PI * pll_center_f;
    //double pll_loop_lowpass_filter_f = 1900;
    // bandwidth is 1728 Hz per line, 3456 Hz / second
    // times 2 (Nyquist-Shannon) = 6912 Hz
    double pll_output_lowpass_filter_f = 650;
    double pll_video_lowpass_filter_f = 400;
    //BiQuadraticFilter biquad_pll_loop_lowpass;
    BiQuadraticFilter biquad_pll_output_lowpass;
    BiQuadraticFilter biquad_video_lowpass;

    public DecodeFax(JWX p) {
        parent = p;
        goertzel_accept = 0.5;
    }

    private void setup() {
        // *** sync time seconds, should be 20 for U.S. locales
        sync_time = 20;
        // update local calibration value
        calibration_val = parent.calibration.get_dvalue();
        state = State.WAITSIG;
        sample_rate = parent.data_rate.get_value();
        sync_interval = 0.025; // 25 ms
        lines_per_second = 2;
        sync_lines = sync_time * lines_per_second;
        // gain value integration time constant
        gain_tc = 1000.0 / sample_rate;
        // automatic frequency control time constant
        sample_increm = sample_rate / (parent.default_image_width * 2.0);
        long_row_len = sample_rate / lines_per_second;
        row_len = (int) long_row_len;
        sampleinterval = 1.0 / sample_rate;
        gain_thresh = 256;
        // the following are receiver slope detector
        // fine-tuning values meant to linearize its response
        cf = 2050.0;
        rcvr_dev = 800;
        bp_q = 1;
        ms_q = 2;
        rcvr_mark = cf + rcvr_dev;
        rcvr_space = cf - rcvr_dev;
        startf = 300.0;
        stopf = 450.0;
        g_size = sample_rate / 4.0;
        gstart = new GoertzelFilter(
                startf * sampleinterval,
                g_size,
                goertzel_accept);
        gend = new GoertzelFilter(
                stopf * sampleinterval,
                g_size,
                goertzel_accept);
        // biquad_pll_loop_lowpass = new BiQuadraticFilter(BiQuadraticFilter.Type.LOWPASS, pll_loop_lowpass_filter_f, sample_rate, invsqr2);
        biquad_pll_output_lowpass = new BiQuadraticFilter(BiQuadraticFilter.Type.LOWPASS, pll_output_lowpass_filter_f, sample_rate, invsqr2);
        biquad_video_lowpass = new BiQuadraticFilter(BiQuadraticFilter.Type.LOWPASS, pll_video_lowpass_filter_f, sample_rate, invsqr2);
        enable_filtering();
    }

    public void init_chart_read(boolean enable) {
        parent.cancel_calibrate();
        if (enable && !enabled) {
            setup();
            old_sign = 0;
            sig = 0;
            sample_count = 0;
            line_time_zero_count = 0;
            image_line = 0;
            //cycle_count = 0;
            //old_count = 0;
            line_time_delta = 0;
            frequency_meter_cycles = 0;
            state = State.WAITSIG;
            linetime_zero = false;
            gstart.reset(true);
            gend.reset(true);
            parent.audio_processor.enable_audio_read(true);
        } else if (!enable && enabled) {
            unlock();
            parent.audio_processor.enable_audio_read(false);
        }
        System.gc();
        enabled = enable;
    }

    public boolean enabled() {
        return enabled;
    }

    // process_data() and all its state functions are threaded by
    // the AudioInputReader class, the source of its audio signals
    protected void process_data(short[] array) {
        for (short v : array) {
            double dv = v;
            // to avoid problems with extended runs,
            // all variables that interact with the
            // sample count must be long
            time_sec = sample_count * sampleinterval;
            linetime_zero = (((sample_count - line_time_delta) % long_row_len) == 0L);
            line_time_zero_count++;

            // PLL block

            gain_level += (Math.abs(dv) - gain_level) * gain_tc;
            gain_level = Math.max(.1, gain_level);
            dv /= gain_level;

            pll_loop_control = dv * pll_reference * pll_loop_gain;
            //pll_loop_control = biquad_pll_loop_lowpass.filter(pll_loop_control);
            pll_integral += pll_loop_control * sampleinterval;
            if (Double.isInfinite(pll_integral)) {
                pll_integral = 0;
            }
            pll_reference = Math.sin(pll_omega * (time_sec + pll_integral));
            wsig = biquad_pll_output_lowpass.filter(pll_loop_control) * pll_output_gain;

            wsig = Math.min(wsig, 2.0);
            wsig = Math.max(wsig, -2.0);
            sign = (pll_reference > 0) ? 1 : -1;
            if (sign > old_sign) {
                frequency_meter_cycles++;
            }
            old_sign = sign;
            gstart.process(wsig);
            gend.process(wsig);
            // low-pass for video
            sig = (video_filter) ? biquad_video_lowpass.filter(wsig) : wsig;
            //now execute machine states
            while (machine_states[state.ordinal()].exec()) {
            }
            if (linetime_zero) {
                image_line++;
                line_time_zero_count = 0;
            }
            sample_count++;
        }
    }

    final class s_waitsig implements MachineState {

        @Override
        public boolean exec() {
            image_line = 0;
            line_time_delta = 0;
            if (gain_level > gain_thresh) {
                state = State.WAITSTB;
                return true;
            }
            return false;
        }
    }

    final class s_waitstb implements MachineState {

        @Override
        public boolean exec() {
            if (gain_level < gain_thresh) {
                state = State.WAITSIG;
                return true;
            } else {
                if (gstart.active()) {
                    state = State.WAITSTE;
                    return true;
                }
            }
            return false;
        }
    }

    final class s_waitste implements MachineState {

        @Override
        public boolean exec() {
            if (!gstart.active()) {
                state = State.WAITLS1;
                return true;
            }
            return false;
        }
    }

    final class s_waitls1 implements MachineState {

        @Override
        public boolean exec() {
            // wait for line start
            if (linetime_zero) {
                sync_array = null;
                state = State.SYNC;
                return true;
            }
            return false;
        }
    }

    final class s_sync implements MachineState {

        @Override
        public boolean exec() {
            if (sync_array == null) {
                // update local calibration value
                calibration_val = parent.calibration.get_dvalue();
                image_line = 0;
                row_index = 0;
                sync_array = new double[row_len];
                sync_line = new double[row_len];
            }
            // must acculumate to work with
            // noisy signals and clock errors
            sync_line[row_index] += wsig;
            row_index++;
            if (row_index >= row_len) {
                row_index = 0;
                // shift result array to correct for clock error
                sync_line = CommonCode.clock_correct_line(
                        sync_line,
                        image_line,
                        row_len * calibration_val);
                // now accumulate result
                for (int i = 0; i < row_len; i++) {
                    sync_array[i] += sync_line[i];
                    sync_line[i] = 0.0;
                }
                if (image_line >= sync_lines) {
                    // integrate and locate negative excursion
                    double iv = sync_array[sync_array.length - 1];
                    double ns, os = (iv > 0) ? 1 : -1;
                    // integration time constant
                    double tc = 200.0 / sample_rate;
                    for (int i = 0; i < row_len; i++) {
                        iv += (sync_array[i] - iv) * tc;
                        ns = (iv > 0) ? 1 : -1;
                        sync_line[i] = ns - os;
                        os = ns;
                    }
                    StringBuilder sb = new StringBuilder();
                    /*for (Double v : sync_line) {
                    sb.append(v);
                    sb.append(",");
                    }
                    sb.append(sync_line[sync_line.length - 1]);
                    parent.write_file("/netbackup/data/python_projects/plot_text_file_content/temp.txt", sb.toString());
                     */
                    double v = sync_line[0];
                    line_time_delta = 0;
                    for (int i = 1; i < row_len; i++) {
                        if (sync_line[i] < v) {
                            v = sync_line[i];
                            line_time_delta = i;
                        }
                    }
                    // adjust for elapsed time of sync calculation
                    line_time_delta += (sync_lines * row_len * calibration_val)
                            // now align sync bar by subtracting a factor
                            // for the integration time constant
                            - (int) (sync_interval * 0.12 * sample_rate);
                    state = State.WAITLS2;
                    return true;
                }
            }
            return false;
        }
    }

    final class s_waitls2 implements MachineState {

        @Override
        public boolean exec() {
            if (linetime_zero) {
                // update for lock-entry contingency
                calibration_val = parent.calibration.get_dvalue();
                chart_panel = parent.new_chart(calibration_val);
                row_index = 0;
                row_pos = 0;
                line_index = 0;
                line_buf = null;
                sig_sum = 0;
                sig_count = 0;
                state = State.PROC;
                return true;
            }
            return false;
        }
    }

    final class s_proc implements MachineState {

        @Override
        public boolean exec() {
            if (linetime_zero) {
                row_index = 0;
                row_pos = 0;
                line_index = 0;
                if (line_buf != null) {
                    chart_panel.image_panel.add_line(line_buf);
                }
                line_buf = new byte[parent.default_image_width];
                // criteria for end of processing
                if (gend.active() || image_line > 4000) {
                    state = State.END;
                    return true;
                }
            }
            if (row_index > row_pos) {
                row_pos += sample_increm;
                if (sig_count > 0) {
                    sig_sum /= sig_count;
                }
                val = (sig_sum + 1.0) * 0.5;
                sig_sum = 0;
                sig_count = 0;
                val = Math.min(val, 1.0);
                val = Math.max(val, 0.0);
                byte b;
                if (grayscale) {
                    b = (byte) (val * 255.0);
                } else {
                    b = (byte) ((val > 0.5) ? 255 : 0);
                }
                line_buf[line_index++] = b;
            } else {
                sig_sum += sig;
                sig_count += 1;
            }
            row_index++;
            return false;
        }
    }

    final class s_end implements MachineState {

        @Override
        public boolean exec() {
            save_chart();
            state = State.WAITSIG;
            return false;
        }
    }

    public void save_chart() {
        if (chart_panel != null) {
            chart_panel.image_panel.receiving_fax = false;
            chart_panel.image_panel.update_image(false);
            chart_panel.image_panel.save_file();
            chart_panel.image_panel.scroll_test(true);
        }
    }

    public boolean receiving_fax() {
        return (enabled && state != State.WAITSIG && state != State.WAITSTB && state != State.END);
    }

    
    //public DecodeFax.State getState() {
    //    return state;
    //}

    public void unlock() {
        save_chart();
        parent.cancel_calibrate();
        state = State.END;
    }

    public void lock() {
        if (!enabled) {
            CommonCode.tell_user(parent, "Must enable receive to lock", "Cannot Lock");
        } else {
            parent.cancel_calibrate();
            state = State.WAITLS2;
        }
    }

    public void periodic_actions() {
        // every 2 seconds for default 500 ms timer
        if (state == State.PROC && chart_panel != null && (timer_counter++ % 4 == 0)) {
            chart_panel.image_panel.update_image(false);
        }
        enable_filtering();
        grayscale = parent.grayscale.get_value();
    }

    public void enable_filtering() {
        video_filter = parent.filter.get_value();
    }
}
