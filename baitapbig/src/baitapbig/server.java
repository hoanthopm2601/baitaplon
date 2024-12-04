
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

public class server {
    private static final int PORT = 5555;
    private static final int COMMENT_PORT = 5556; // Port cho bình luận
    private static List<Socket> clientSockets = new ArrayList<>(); // Kết nối hình ảnh
    private static List<Socket> commentSockets = new ArrayList<>(); // Kết nối bình luận
    private static JLabel imageLabel;
    private static JTextArea commentArea;
    private static JLabel viewerCountLabel; // JLabel để hiển thị số người xem
    private static volatile boolean capturing = false;
    private static volatile boolean paused = false;
    private static Thread captureThread;
    private static int heartCount = 0; // Đếm số thả tim

    public static void main(String[] args) {
        JFrame frame = new JFrame("Screen Share Server");
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        imageLabel = new JLabel("Nhấn 'Start Capture' để bắt đầu", JLabel.CENTER);
        frame.add(imageLabel, BorderLayout.CENTER);

        JButton toggleButton = new JButton("Start Capture");
        toggleButton.addActionListener(e -> toggleCapture(toggleButton));
        frame.add(toggleButton, BorderLayout.SOUTH);

        // JTextArea để hiển thị bình luận
        commentArea = new JTextArea(5, 20);
        commentArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(commentArea);
        frame.add(scrollPane, BorderLayout.NORTH);

        // Label để hiển thị số lượng thả tim
        JLabel heartLabel = new JLabel("Thả Tim: 0", JLabel.CENTER);
        frame.add(heartLabel, BorderLayout.WEST);

        // Label để hiển thị số người xem
        viewerCountLabel = new JLabel("Số người xem: 0", JLabel.CENTER);
        frame.add(viewerCountLabel, BorderLayout.EAST);

        frame.setVisible(true);

        // Bắt đầu lắng nghe client
        startListening();
    }

    private static void toggleCapture(JButton toggleButton) {
        if (capturing) {
            paused = !paused;
            toggleButton.setText(paused ? "Resume Capture" : "Stop Capture");
        } else {
            capturing = true;
            paused = false;
            toggleButton.setText("Stop Capture");
            startCapture();
        }
    }

    private static void startCapture() {
        captureThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                imageLabel.setText("Server started, waiting for connection...");

                Robot robot = new Robot();
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

                while (capturing) {
                    BufferedImage screenImage = robot.createScreenCapture(screenRect);
                    BufferedImage resizedImage = resizeImage(screenImage, 800, 600);

                    SwingUtilities.invokeLater(() -> {
                        imageLabel.setIcon(new ImageIcon(resizedImage));
                    });

                    // Kiểm tra và chấp nhận kết nối mới từ client
                    serverSocket.setSoTimeout(50);
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSockets.add(clientSocket);
                        updateViewerCount(); // Cập nhật số người xem
                        imageLabel.setText("Client connected. Total clients: " + clientSockets.size());
                    } catch (SocketTimeoutException e) {
                        // Không làm gì, tiếp tục xử lý các client đã kết nối
                    }

                    // Gửi dữ liệu màn hình tới tất cả các client
                    for (int i = 0; i < clientSockets.size(); i++) {
                        Socket clientSocket = clientSockets.get(i);
                        try {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(screenImage, "png", baos);
                            byte[] imageBytes = baos.toByteArray();

                            DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
                            dos.writeInt(imageBytes.length);
                            dos.write(imageBytes);
                            dos.flush();
                        } catch (IOException e) {
                            // Nếu client ngắt kết nối, xóa khỏi danh sách
                            try {
                                clientSocket.close();
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                            clientSockets.remove(i);
                            i--;
                            updateViewerCount(); // Cập nhật số người xem
                            System.out.println("Client disconnected. Total clients: " + clientSockets.size());
                        }
                    }

                    Thread.sleep(5);
                }

                for (Socket clientSocket : clientSockets) {
                    clientSocket.close();
                }
                clientSockets.clear();

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        captureThread.start();
    }

    private static BufferedImage resizeImage(BufferedImage originalImage, int width, int height) {
        Image tmp = originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resizedImage.createGraphics();
        g2d.drawImage(tmp, 0, 0, null);
        g2d.dispose();
        return resizedImage;
    }

    private static void startListening() {
        new Thread(() -> {
            try (ServerSocket commentServerSocket = new ServerSocket(COMMENT_PORT)) { 
                while (true) {
                    Socket clientSocket = commentServerSocket.accept();
                    commentSockets.add(clientSocket);
                    updateViewerCount(); // Cập nhật số người xem
                    new Thread(() -> {
                        try {
                            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
                            while (true) {
                                String input = dis.readUTF();
                                if (input.equals("HEART")) {
                                    handleHeart();
                                } else {
                                    broadcastComment(input);
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            // Khi client ngắt kết nối, cập nhật số người xem
                            commentSockets.remove(clientSocket);
                            updateViewerCount(); // Cập nhật số người xem
                        }
                    }).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void handleHeart() {
        heartCount++;
        SwingUtilities.invokeLater(() -> {
            commentArea.append("Thả Tim! Tổng số thả tim: " + heartCount + "\n");
            // Cập nhật số thả tim trên giao diện
            ((JLabel) ((JPanel) commentArea.getParent()).getComponent(1)).setText("Thả Tim: " + heartCount);
        });
        // Gửi thông báo đến tất cả client
        for (Socket clientSocket : commentSockets) {
            try {
                DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
                dos.writeUTF("Server: Một người đã thả tim!");
                dos.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void broadcastComment(String comment) {
        SwingUtilities.invokeLater(() -> {
            commentArea.append("Client: " + comment + "\n");
        });
        for (Socket clientSocket : commentSockets) {
            try {
                DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
                dos.writeUTF("Server: " + comment);
                dos.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void updateViewerCount() {
        SwingUtilities.invokeLater(() -> {
            viewerCountLabel.setText("Số người xem: " + (commentSockets.size() + clientSockets.size()));
        });
    }
}