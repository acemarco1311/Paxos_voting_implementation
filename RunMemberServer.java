import java.util.*; 
import java.net.*; 
import java.io.*; 
import java.util.concurrent.locks.*; 

// this thread is responsible for starting the member server and get requests
public class RunMemberServer implements Runnable{
    private CouncilMember member; 

    // Thread constructor
    // input: CouncilMember 
    // output: no 
    public RunMemberServer(CouncilMember member){
        this.member = member; 
    }

    // run the server to get requests 
    // input: no 
    // output: no 
    @Override
    public void run(){
        while(true){
            try{
                Socket socket = this.member.getMemberServerSocket().accept(); 
                // when receive a request
                // create a new thread to respond
                AcceptorResponseToRequest respondToRequest = new AcceptorResponseToRequest(this.member, socket); 
                Thread executingRequest = new Thread(respondToRequest); 
                executingRequest.start(); 
            }
            // if the server socket has been closed, shutdown this thread
            catch(SocketException se){
                break; 
            }
            catch(Exception e){
                System.out.println("Error happened while runinng the member server."); 
                e.printStackTrace(); 
            }
        }
    }
}
