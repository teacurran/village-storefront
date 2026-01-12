package villagecompute.storefront.storecredit;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.api.types.StoreCreditAccountDto;
import villagecompute.storefront.api.types.StoreCreditTransactionDto;
import villagecompute.storefront.data.models.StoreCreditAccount;
import villagecompute.storefront.data.models.StoreCreditTransaction;
import villagecompute.storefront.data.models.User;

/**
 * Mapper for store credit ledger entities.
 */
@ApplicationScoped
public class StoreCreditMapper {

    public StoreCreditAccountDto toDto(StoreCreditAccount account) {
        StoreCreditAccountDto dto = new StoreCreditAccountDto();
        dto.id = account.id;
        dto.userId = account.user != null ? account.user.id : null;
        dto.userEmail = account.user != null ? account.user.email : null;
        dto.userName = resolveUserName(account.user);
        dto.balance = account.balance;
        dto.currency = account.currency;
        dto.status = account.status;
        dto.notes = account.notes;
        dto.metadata = account.metadata;
        dto.createdAt = account.createdAt;
        dto.updatedAt = account.updatedAt;
        return dto;
    }

    public StoreCreditTransactionDto toDto(StoreCreditTransaction transaction) {
        StoreCreditTransactionDto dto = new StoreCreditTransactionDto();
        dto.id = transaction.id;
        dto.accountId = transaction.account != null ? transaction.account.id : null;
        dto.orderId = transaction.orderId;
        dto.giftCardId = transaction.giftCard != null ? transaction.giftCard.id : null;
        dto.amount = transaction.amount;
        dto.transactionType = transaction.transactionType;
        dto.balanceAfter = transaction.balanceAfter;
        dto.reason = transaction.reason;
        dto.posDeviceId = transaction.posDeviceId;
        dto.createdAt = transaction.createdAt;
        return dto;
    }

    private String resolveUserName(User user) {
        if (user == null) {
            return null;
        }
        String first = user.firstName != null ? user.firstName.trim() : "";
        String last = user.lastName != null ? user.lastName.trim() : "";
        String combined = (first + " " + last).trim();
        return combined.isEmpty() ? null : combined;
    }
}
