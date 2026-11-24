public class Main {
    public static void main(String[] args) {
        BankAccount checking = new BankAccount("Checking", 500.0);
        SavingsAccount savings = new SavingsAccount("Savings", 1200.0, 0.04);

        checking.deposit(250.0);
        boolean paid = checking.withdraw(900.0);
        savings.addInterest();

        System.out.println(checking);
        System.out.println(savings);
        System.out.println("Withdrawal of 900 approved: " +paid);
    }
}
