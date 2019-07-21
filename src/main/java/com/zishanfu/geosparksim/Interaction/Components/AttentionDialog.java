package com.zishanfu.geosparksim.Interaction.Components;

import javax.swing.*;
import java.awt.*;

public class AttentionDialog {

    public AttentionDialog(String frameName, String content) {
        Frame dialogframe = new Frame();
        JDialog d1 = new JDialog(dialogframe, frameName, true);
        Label label= new Label(content,Label.CENTER);
        d1.add(label);
        d1.setSize(400,200);
        d1.setVisible(true);
        d1.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    }
}
