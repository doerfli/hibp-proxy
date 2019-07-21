require 'sidekiq'
require 'sinatra/base'
require_relative 'worker'

Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

class Web < Sinatra::Application
  get '/search' do
    email = params[:email]
    device_token = params[:device_token]
    #puts "#{email} - #{device_token}"
    BgWorker.perform_async(email, device_token)
    "enqueued request for #{device_token}"
  end
end
