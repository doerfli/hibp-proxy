require 'sidekiq'
require 'sinatra/base'
require_relative 'worker'

Sidekiq.configure_client do |config|
  config.redis = { url: ENV['REDIS_URL'] }
end

class Web < Sinatra::Application
  get '/search' do
    email = params[:email]
    BgWorker.perform_async(email)
    "enqueued #{email}"
  end
end
