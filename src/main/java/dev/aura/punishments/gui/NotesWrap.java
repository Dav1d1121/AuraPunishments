package dev.aura.punishments.gui;

import dev.aura.punishments.util.Text;

import java.util.ArrayList;
import java.util.List;

final class NotesWrap {
    private NotesWrap() {}

    static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        String rest = Text.clean(s);
        int guard = 0;
        while (rest.length() > width && guard++ < 12) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut < width / 3) cut = width;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }
}
