import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

public class LevelSelector {
    private final JFrame frame = new JFrame("Mario AI - Niveles generados");
    private final DefaultListModel<File> levelModel = new DefaultListModel<>();
    private final JList<File> levelList = new JList<>(levelModel);
    private final JLabel countLabel = new JLabel();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LevelSelector().show());
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(560, 420));
        frame.setLayout(new BorderLayout(12, 12));

        JLabel title = new JLabel("Selecciona un nivel generado");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JPanel header = new JPanel(new GridLayout(2, 1));
        header.setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 16));
        header.add(title);
        header.add(countLabel);

        levelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        levelList.setFont(levelList.getFont().deriveFont(16f));
        levelList.setCellRenderer((list, file, index, selected, focused) -> {
            JLabel label = new JLabel(file.getName());
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
        JButton playButton = new JButton("Jugar nivel seleccionado");
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
        File directory = new File("../levels/generated");
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".txt"));
        if (files != null) {
            List<File> sorted = Arrays.stream(files)
                    .sorted(Comparator.comparing(File::getName))
                    .collect(Collectors.toList());
            sorted.forEach(levelModel::addElement);
        }
        updateCount();
        if (levelModel.isEmpty()) {
            countLabel.setText("No hay niveles generados todavía.");
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
