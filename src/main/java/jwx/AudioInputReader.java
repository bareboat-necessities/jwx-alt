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
 *
 * Reading data from a targetdataline has a serious bug as of 03/2011:
 *
 * 1. After 16384 seconds of data, the value returned by "available()"
 * becomes a large negative value with no relation to the actual data.
 *
 * 2. At all times on some machines, calling read() expecting
 * to be blocked harmlessly will eat up the
 * processor's entire capacity instead of simply sleeping.
 *
 * SO:
 *
 * 1. Don't allow a reader to live longer than 16384 seconds, i.e. 2^14
 * or 4h 33m 4s. This class now resets the reader about every hour, when
 * there is no chart being received.
 *
 * 2. Don't call read() until there is enough data to fill the supplied
 * buffer, i.e. there is no chance of blocking. Call available() and
 * sleep if available data is less than the bufffer size.
 *
 *
 */
package jwx;

import javax.sound.sampled.*;
import java.nio.*;

/**
 *
 * @author lutusp
 */
final class AudioInputReader extends Thread {

    final AudioProcessor audio_processor;
    final JWX parent;
    TargetDataLine targetDataLine = null;
    double dv;
    final int mask = 0xff;
    final short[] short_buf;
    final byte[] byte_buf;
    final int word_size;
    int avail;
    // reset audio stream once per hour when idle
    final double restart_cycle_time_sec = 3600;

    public AudioInputReader(AudioProcessor ap, JWX p, int ws) {
        audio_processor = ap;
        parent = p;
        word_size = ws;
        //sbufsz = bbufsz / 2;
        byte_buf = new byte[audio_processor.bbufsz];
        short_buf = new short[audio_processor.sbufsz];
    }

    private void open_target_line() {
        try {
            if (targetDataLine == null) {
                Mixer.Info mi;
                try {
                    int n = audio_processor.target_mixer_index;
                    mi = audio_processor.target_mixer_list.get(n);
                } catch (Exception e) {
                    System.out.println(e + ", proceeding with index 0");
                    mi = audio_processor.target_mixer_list.get(0);
                }
                Mixer mixer = AudioSystem.getMixer(mi);
                targetDataLine = (TargetDataLine) mixer.getLine(audio_processor.targetLineInfo);
                // provide the desired buffer size
                targetDataLine.open(audio_processor.audioFormat, audio_processor.bbufsz * 2);
                targetDataLine.start();
            }
        } catch (LineUnavailableException e) {
            targetDataLine = null;
            CommonCode.p("open_target_line: " + e);
            audio_processor.read_valid = false;
        }
    }

    private void close_target_line() {
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.close();
            targetDataLine = null;
        }
    }

    // deal with a nasty audio line bug that will
    // lock up the computer if not managed.
    // if not receiving a fax, and if time > max_time
    // then restart the audio stream
    public void restart_stream_test() {
        if (parent.decode_fax.time_sec > parent.reset_target_time && !parent.decode_fax.receiving_fax()) {
            if (parent.debug) {
                CommonCode.p(getClass().getSimpleName() + ": restarting target audio line");
            }
            // this brutal remedy seems necessary
            close_target_line();
            open_target_line();
            parent.reset_target_time = parent.decode_fax.time_sec + restart_cycle_time_sec;
        }
    }

    @Override
    public void run() {
        audio_processor.read_valid = true;
        audio_processor.read_enable = true;
        parent.reset_target_time = -1;
        restart_stream_test();
        try {
            while (targetDataLine != null && audio_processor.read_enable) {
                // wait until full buffer length of data is available
                // before calling read()
                while (audio_processor.read_enable && (avail = targetDataLine.available()) < audio_processor.bbufsz && avail >= 0) {
                    Thread.sleep(20);
                }
                if (audio_processor.read_enable) {
                    // process the acquired audio data
                    parent.audio_read = targetDataLine.read(byte_buf, 0, audio_processor.bbufsz);
                    if (parent.audio_read > 0) {
                        ShortBuffer sb = ByteBuffer.wrap(byte_buf).asShortBuffer();
                        sb.get(short_buf);
                        parent.decode_fax.process_data(short_buf);
                        if (audio_processor.write_valid) {
                            audio_processor.write_output(short_buf);
                        }
                    }
                }
                restart_stream_test();
            }
            close_target_line();
            audio_processor.read_valid = false;
        } catch (InterruptedException e) {
            CommonCode.p("audio input reader: " + e);
            close_target_line();
            audio_processor.read_valid = false;
        }
    }
}
