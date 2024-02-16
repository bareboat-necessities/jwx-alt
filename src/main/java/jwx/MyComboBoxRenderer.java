/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jwx;

import java.awt.Component;
import java.util.ArrayList;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 *
 * @author lutusp
 * @param <E>
 */
public class MyComboBoxRenderer<E> extends JLabel implements ListCellRenderer<E> {

    static final long serialVersionUID = 9379;
    ArrayList<java.lang.String> tooltips;
    
    public MyComboBoxRenderer(ArrayList<java.lang.String> tt) {
        tooltips = tt;
    }
   
        @Override
        public Component getListCellRendererComponent(JList<? extends E> list, E value,
                int index, boolean isSelected, boolean has_focus) {
           
            if (isSelected) {
               setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
                if (index >= 0 && tooltips != null && index < tooltips.size()) {
                    list.setToolTipText(tooltips.get(index));
                }
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }
            setFont(list.getFont());
            setText((value == null) ? "" : value.toString());
            return this;
        }
    };
