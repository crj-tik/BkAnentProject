import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
public class CheckMcp {
  public static void main(String[] args) {
    System.out.println(McpClient.class.getName());
    System.out.println(McpSyncClient.class.getName());
  }
}
