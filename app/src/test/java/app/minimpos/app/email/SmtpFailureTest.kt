package app.minimpos.app.email

import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.terminal.transport.ExternalText
import com.google.common.truth.Truth.assertThat
import com.sun.mail.smtp.SMTPAddressFailedException
import org.junit.Test
import java.net.ConnectException
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress

/** Plain JUnit: how JavaMail's exceptions become typed email faults, keeping only the mail server's own reply. */
class SmtpFailureTest {
    @Test
    fun `a refused login keeps the server's reply on one line`() {
        val failure = AuthenticationFailedException("535-5.7.8 Username and Password not accepted.\n535 5.7.8 Learn more").emailFailure()
        val reply = ExternalText("535-5.7.8 Username and Password not accepted. 535 5.7.8 Learn more")
        assertThat(failure).isEqualTo(Failure.Email(EmailFault.AUTHENTICATION, reply))
    }

    @Test
    fun `a refused recipient keeps the server's reply, not JavaMail's summary`() {
        val address = InternetAddress("nobody@example.com")
        val refused = SMTPAddressFailedException(address, "RCPT TO", 550, "550 5.1.1 User unknown")
        val none = emptyArray<Address>()
        val failure = SendFailedException("Invalid Addresses", refused, none, none, arrayOf(address)).emailFailure()
        assertThat(failure).isEqualTo(Failure.Email(EmailFault.REJECTED, ExternalText("550 5.1.1 User unknown")))
    }

    @Test
    fun `invalid addresses and unreachable servers say what to fix, without JavaMail's words`() {
        assertThat(AddressException("Illegal address").emailFailure()).isEqualTo(Failure.Email(EmailFault.INVALID_ADDRESS))
        val unreachable = MessagingException("Couldn't connect to host, port: smtp.example.com, 587", ConnectException("refused"))
        assertThat(unreachable.emailFailure()).isEqualTo(Failure.Email(EmailFault.UNREACHABLE))
    }
}
