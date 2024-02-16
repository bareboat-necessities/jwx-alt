/** *************************************************************************
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
 ************************************************************************** */
package jwx;

import javax.swing.*;
import java.lang.reflect.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.io.*;

/**
 *
 * @author lutusp
 */
final public class ConfigManager {

    final String line_sep;
    final String init_path;
    final JFrame parent;
    final Pattern pat;
    Matcher mat;
    ConcurrentSkipListMap<String, ControlInterface> map;

    public ConfigManager(JFrame p, String path) {
        parent = p;
        init_path = path;
        pat = Pattern.compile("\\s*(.+?)\\s*=\\s*(.+?)\\s*");
        line_sep = System.getProperty("line.separator");
        create_control_map();
        read_config_file();
    }

    // locate all parent fields that
    // implement ControlInterface
    private void create_control_map() {
        map = new ConcurrentSkipListMap<>();
        String name;
        for (Field f : parent.getClass().getDeclaredFields()) {
            name = f.getName();
            try {
                Object obj = f.get(parent);
                if (obj != null) {
                    Class<?> cls = obj.getClass();
                    if (cls != null) {
                        Type[] type = cls.getGenericInterfaces();
                        if (type.length > 0) {
                            Object o = type[0];
                            if (o != null) {
                                String t = o.toString();
                                if (t.equals("interface jwx.ControlInterface")) {
                                    map.put(name, (ControlInterface) obj);
                                }
                            }
                        }
                    }
                }
            } catch (IllegalAccessException | IllegalArgumentException e) {
                //System.out.println(e + " = " + name);
            }
        }
    }

    private void read_config_file() {
        try {
            File f = new File(init_path);
            if (f.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        mat = pat.matcher(line);
                        if (mat.matches()) {
                            map.get(mat.group(1)).set_value(mat.group(2));
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    public void write_config_file() {
        try {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(init_path))) {
                for (String key : map.keySet()) {
                    String val = map.get(key).toString();
                    bw.write(key + " = " + val + line_sep);
                }
            }
        } catch (IOException e) {
            e.printStackTrace(System.out);
        }
    }
}
