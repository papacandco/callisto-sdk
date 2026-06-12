# frozen_string_literal: true

module Callisto
  # Internal base for read models. Subclasses declare their fields via +fields+;
  # +from_hash+ keeps only known fields (string or symbol keys) and ignores the rest,
  # so new API fields never break decoding.
  class Model
    # @return [Array<Symbol>] declared field names
    def self.fields
      @fields ||= []
    end

    # Declares readable fields for the model.
    def self.fields=(names)
      @fields = names.map(&:to_sym)
      attr_reader(*@fields)
    end

    # Builds an instance from a decoded hash, ignoring unknown keys.
    #
    # @param data [Hash, nil]
    # @return [Model]
    def self.from_hash(data)
      data ||= {}
      obj = allocate
      fields.each do |name|
        value = if data.key?(name.to_s)
                  data[name.to_s]
                elsif data.key?(name)
                  data[name]
                end
        obj.instance_variable_set("@#{name}", value)
      end
      obj
    end

    # @return [Hash] the model as a hash of declared fields
    def to_h
      self.class.fields.each_with_object({}) do |name, acc|
        acc[name] = instance_variable_get("@#{name}")
      end
    end
  end

  # Account balance.
  class Balance < Model
    self.fields = %i[credit currency sms_price_local sms_price_international]
  end

  # Result of sms.send.
  class SendSmsResult < Model
    self.fields = %i[total_amount available_credit status recipient_count scheduled messages]
  end

  # Result of otp.send.
  class SendOtpResult < Model
    self.fields = %i[id provider recipient expires_at expires_in]
  end

  # Result of otp.verify.
  class VerifyOtpResult < Model
    self.fields = %i[id status verified verified_at]
  end

  # Result of a whatsapp.send* call.
  class SendWaResult < Model
    self.fields = %i[id instance_id recipient message_type status scheduled media_url]
  end

  # Result of notify.send.
  class NotifyResult < Model
    self.fields = %i[status topic queued_events topic_messages]
  end

  # A single SMS message record.
  class SmsMessage < Model
    self.fields = %i[id sender_name recipient content status created_at updated_at]
  end

  # A single OTP record. Carries both +otp_id+ (get_status) and +id+ (list rows).
  class Otp < Model
    self.fields = %i[otp_id id status recipient expires_at verified_at attempts created_at]
  end

  # A WhatsApp instance.
  class WhatsAppInstance < Model
    self.fields = %i[
      id code client_id name phone_number phone_name status billing_status
      trial_days_remaining monthly_fee messages_sent_today messages_sent_month
      daily_limit last_message_at webhook_url is_active created_at updated_at
    ]
  end

  # A WhatsApp message record.
  class WhatsAppMessage < Model
    self.fields = %i[
      id instance_id client_id client_api_id recipient recipient_name message_type
      content media_url media_mimetype media_filename extra_data direction status
      whatsapp_message_id error_code error_message retry_count is_billable cost
      sent_at delivered_at read_at scheduled_at created_at updated_at processor_identifier
    ]
  end

  # Generic page container returned by list methods.
  class Paginated
    attr_reader :items, :total, :per_page, :current_page, :next, :previous, :total_pages

    def initialize(items:, total:, per_page:, current_page:, next_page:, previous:, total_pages:)
      @items = items
      @total = total
      @per_page = per_page
      @current_page = current_page
      @next = next_page
      @previous = previous
      @total_pages = total_pages
    end

    # Builds a Paginated from a decoded hash, mapping each item via +item_factory+.
    #
    # @param data [Hash, nil]
    # @param item_factory [#call] callable that builds a typed item from a hash
    # @return [Paginated]
    def self.from_hash(data, item_factory)
      data ||= {}
      raw_items = data["items"] || data[:items] || []
      Paginated.new(
        items: raw_items.map { |i| item_factory.call(i) },
        total: data["total"] || data[:total] || 0,
        per_page: data["per_page"] || data[:per_page] || 0,
        current_page: data["current_page"] || data[:current_page] || 0,
        next_page: data["next"] || data[:next],
        previous: data["previous"] || data[:previous],
        total_pages: data["total_pages"] || data[:total_pages] || 0
      )
    end
  end
end
