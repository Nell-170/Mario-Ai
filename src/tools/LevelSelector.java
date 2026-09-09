import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

public class LevelSelector {
    private final JFrame frame = new JFrame("Mario AI - Selector de Niveles");
    private final DefaultListModel<File> levelModel = new DefaultListModel<>();
    private final JList<File> levelList = new JList<>(levelModel);
    private final JLabel countLabel = new JLabel();
    private final JComboBox<String> folderCombo = new JComboBox<>(new String[]{
        "Niveles base (Nivel 0)",
        "Niveles generados por MarioGPT",
        "Niveles convertidos (Todos)"
    });

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LevelSelector().show());
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(620, 480));
        frame.setLayout(new BorderLayout(12, 12));

        JLabel title = new JLabel("Selector de Niveles de Mario");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));

        JPanel header = new JPanel(new GridLayout(3, 1, 0, 6));
        header.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
        header.add(title);
        
        JPanel comboPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        comboPanel.add(new JLabel("Categoría: "));
        comboPanel.add(folderCombo);
        header.add(comboPanel);
        header.add(countLabel);

        folderCombo.addActionListener(e -> loadLevels());

        levelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        levelList.setFont(levelList.getFont().deriveFont(15f));
        levelList.setCellRenderer((list, file, index, selected, focused) -> {
            JLabel label = new JLabel(file.getName() + " (" + file.getParentFile().getName() + ")");
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        levelList.addListSelectionListener(event -> updateCount());
        levelList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && !levelList.isSelectionEmpty()) {
                    playSelected();
                }
            }
        });

        JButton refreshButton = new JButton("Actualizar");
        refreshButton.addActionListener(event -> loadLevels());
        JButton playButton = new JButton("¡Jugar nivel seleccionado!");
        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD));
        playButton.addActionListener(event -> playSelected());
        JButton closeButton = new JButton("Cerrar");
        closeButton.addActionListener(event -> frame.dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
        actions.add(refreshButton);
        actions.add(closeButton);
        actions.add(playButton);

        frame.add(header, BorderLayout.NORTH);
        frame.add(new JScrollPane(levelList), BorderLayout.CENTER);
        frame.add(actions, BorderLayout.SOUTH);
        frame.setLocationRelativeTo(null);
        loadLevels();
        frame.setVisible(true);
    }

    private void loadLevels() {
        levelModel.clear();
        String selectedCategory = (String) folderCombo.getSelectedItem();
        List<File> directories = new ArrayList<>();

        if ("Niveles base (Nivel 0)".equals(selectedCategory)) {
            directories.add(new File("../src/levels/nivel0"));
            directories.add(new File("../levels/nivel0"));
        } else if ("Niveles generados por MarioGPT".equals(selectedCategory)) {
            directories.add(new File("../src/levels/generated"));
            directories.add(new File("../levels/generated"));
        } else {
            directories.add(new File("../src/levels/converted"));
            directories.add(new File("../levels/converted"));
        }

        for (File dir : directories) {
            if (!dir.exists()) continue;
            File[] files = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".txt"));
            if (files != null) {
                List<File> sorted = Arrays.stream(files)
                        .sorted(Comparator.comparing(File::getName))
                        .collect(Collectors.toList());
                sorted.forEach(file -> {
                    if (!levelModel.contains(file)) {
                        levelModel.addElement(file);
                    }
                });
            }
        }
        updateCount();
        if (levelModel.isEmpty()) {
            countLabel.setText("No hay niveles en esta categoría todavía.");
        }
    }

    private void updateCount() {
        if (!levelModel.isEmpty()) {
            countLabel.setText(levelModel.size() + " nivel(es) disponible(s)");
        }
    }

    private void playSelected() {
        if (levelList.isSelectionEmpty()) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Selecciona un nivel antes de jugar.",
                    "Nivel no seleccionado",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        File selected = levelList.getSelectedValue();
        frame.dispose();
        Thread gameThread = new Thread(() -> {
            try {
                PlayHuman.main(new String[] { selected.getPath() });
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null,
                        "No se pudo iniciar el nivel:\n" + exception.getMessage(),
                        "Error al iniciar Mario",
                        JOptionPane.ERROR_MESSAGE));
            }
        }, "mario-human-game");
        gameThread.start();
    }
}
