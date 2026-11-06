package still.core
import java.net.InetAddress
import kotlin.test.*
class AddressTest {
 @Test fun publicIpv6WithLeadingZeroIsAllowed() {
  val address=InetAddress.getByName("64:ff9b::cff1:e002")
  assertEquals(listOf(address),publicAddresses("archive.org",listOf(address)))
 }
 @Test fun mixedAnswersRetainOnlyPublic() {
  val public=InetAddress.getByName("207.241.224.2")
  assertEquals(listOf(public),publicAddresses("archive.org",listOf(InetAddress.getByName("192.168.1.1"),public)))
 }
 @Test fun privateAnswersStillBlocked() {
  for(ip in listOf("127.0.0.1","0.1.2.3","10.0.0.1","169.254.169.254","100.64.0.1","::1","fc00::1","fe80::1")) {
   assertFailsWith<java.net.UnknownHostException>{publicAddresses("test",listOf(InetAddress.getByName(ip)))}
  }
 }
}
