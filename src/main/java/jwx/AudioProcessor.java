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

import javax.sound.sampled.*;
import java.nio.*;

/**
 *
 * @author lutusp
 */
final public class AudioProcessor {

    JWX parent;
    SourceDataLine audioOutputLine = null;
    AudioFormat audioFormat = null;
    // must be 2 bytes -- windows XP doesn't support 32 bits
    final int word_size = 2;
    final int sbufsz = 4096;
    final int bbufsz = sbufsz * word_size;
    boolean read_enable = false;
    AudioInputReader audio_reader = null;
    final byte[] out_buffer = new byte[bbufsz];
    Line.Info targetLineInfo;
    Line.Info sourceLineInfo;
    boolean read_valid = false;
    boolean write_valid = false;
    java.util.List<Mixer.Info> source_mixer_list, target_mixer_list;
    int source_mixer_count, target_mixer_count;
    int target_mixer_index = -1;
    int source_mixer_index = -1;
    int new_index = -1;

    public AudioProcessor(JWX p) {
        parent = p;
        targetLineInfo = new Line.Info(TargetDataLine.class);
        sourceLineInfo = new Line.Info(SourceDataLine.class);
        Mixer.Info[] mi_list = AudioSystem.getMixerInfo();
        source_mixer_list = new java.util.ArrayList<>();
        target_mixer_list = new java.util.ArrayList<>();
        for (Mixer.Info mi : mi_list) {
            Mixer mixer = AudioSystem.getMixer(mi);
            if (mixer.isLineSupported(targetLineInfo)) {
                target_mixer_list.add(mi);
                //CommonCode.p("accept target " + mixer);
            }
            if (mixer.isLineSupported(sourceLineInfo)) {
                source_mixer_list.add(mi);
                //CommonCode.p("accept source " + mixer);
            }
        }
        target_mixer_count = target_mixer_list.size();
        source_mixer_count = source_mixer_list.size();
    }

    public void periodic_actions() {
        enable_audio_write_check();
    }

    public boolean read_valid() {
        return read_valid;
    }

    public boolean write_valid() {
        return write_valid;
    }

    public void enable_audio_read(boolean enable) {
        audioFormat = createAudioFormat();
        target_mixer_index = parent.audio_input.get_value() - 1;
        read_enable = enable;
        if (enable) {
            audio_reader = new AudioInputReader(this, parent, word_size);
            audio_reader.start();
        } else {
            try {
                if (audio_reader != null) {
                    read_enable = false;
                    audio_reader.join();
                    audio_reader = null;
                }
            } catch (InterruptedException e) {
                CommonCode.p("join audio thread: " + e);
                read_enable = false;
            }
        }
        enable_audio_write(enable && parent.monitor_volume.get_value() != 0);
    }

    private void close_source_line() {
        if (audioOutputLine != null) {
            audioOutputLine.flush();
            audioOutputLine.stop();
            audioOutputLine.close();
            audioOutputLine = null;
        }
    }

    private boolean open_source_line() {
        try {
            close_source_line();
            audioFormat = createAudioFormat();
            Mixer.Info mi;
            try {
                mi = source_mixer_list.get(new_index);
            } catch (Exception e) {
                System.out.println(e + ", proceeding with index 0");
                mi = source_mixer_list.get(0);
            }
            Mixer mixer = AudioSystem.getMixer(mi);
            audioOutputLine = (SourceDataLine) mixer.getLine(sourceLineInfo);
            audioOutputLine.open(audioFormat);
            audioOutputLine.start();
            return true;
        } catch (LineUnavailableException e) {
            audioOutputLine = null;
            System.out.println("open_source_line: " + e);
            return false;
        }
    }

    public void enable_audio_write(boolean enable) {
        new_index = parent.audio_output.get_value();
        if (new_index >= source_mixer_count) {
            new_index = 0;
        }
        if (enable && parent.monitor_volume.get_percent_value() != 0) {
            if (open_source_line()) {
                write_valid = true;
            }

        } else {
            write_valid = false;
            close_source_line();
        }
        source_mixer_index = new_index;
    }

    public void enable_audio_write_check() {
        boolean vol_state = parent.monitor_volume.get_percent_value() != 0;
        if (read_valid && (vol_state != write_valid || parent.audio_output.get_value() != source_mixer_index)) {
            enable_audio_write(read_valid);
        }
    }

    public boolean reading() {
        return read_enable;
    }

    AudioFormat createAudioFormat() {
        int sampleSizeInBits = 8 * word_size;
        int channels = 1;
        boolean signed = true;
        boolean bigEndian = true;
        float rate = parent.data_rate.get_value();
        return new AudioFormat(
                rate,
                sampleSizeInBits,
                channels,
                signed,
                bigEndian);
    }

    public void write_output(short[] data) {
        double gain = parent.monitor_volume.get_percent_value();
        ByteBuffer byb = ByteBuffer.wrap(out_buffer);
        // must loop through because of gain setting
        for (short sv : data) {
            byb.putShort((short) (sv * gain));
        }
        audioOutputLine.write(out_buffer, 0, out_buffer.length);
    }
}
