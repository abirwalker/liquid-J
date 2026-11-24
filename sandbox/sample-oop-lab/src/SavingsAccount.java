public class SavingsAccount extends BankAccount {
    private final double rate;

    public SavingsAccount(String owner, double openingBalance, double rate) {
        super(owner, openingBalance);
        this.rate = rate;
    }

    /* Interest is added once per call, e.g. at the end of a month. */
    public void addInterest() {
        balance += balance * rate;
    }
}
