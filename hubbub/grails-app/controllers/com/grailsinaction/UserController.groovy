package com.grailsinaction

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken as AuthToken
import org.springframework.security.core.context.SecurityContextHolder as SCH

class UserController {

    def scaffold = true
    
    def delete = {
        def user = User.get( params.id )
        if (user) {
            try {
                UserRole.removeAll user
                user.delete()
                flash.message = "User ${params.id} deleted"
                redirect action: "list"
            }
            catch(org.springframework.dao.DataIntegrityViolationException e) {
                flash.message = "User ${params.id} could not be deleted"
                redirect action: "show", id: params.id
            }
        }
        else {
            flash.message = "User not found with id ${params.id}"
            redirect action:"list"
        }
    }

    // In the book, the value for 'isVisible' is a closure, but a bug
    // in the Nav plugin means that this doesn't work with WebFlow
    // services. So here we use the simple boolean value.
    static navigation = [
        [group:'tabs', action:'search', order: 90],
        [action: 'advSearch', title: 'Advanced Search', order: 95],
        [action: 'register', title: 'Register', order: 99, isVisible: { !springSecurityService.isLoggedIn() }]
    ]

    def springSecurityService

    def search = {

    }

    def results = {

        def users = User.findAllByUserIdLike("%${params.userId}%")
        return [ users: users, term : params.userId ]

    }

    def advSearch = {
        
    }

    def advResults = {

        def profileProps = Profile.metaClass.properties*.name
        def profiles = Profile.withCriteria {
            "${params.queryType}" {

                params.each { field, value ->

                    if (profileProps.grep(field) && value) {
                        ilike(field, value)
                    }
                }

            }

        }
        [ profiles : profiles ]

    }

    def register = { UserRegistrationCommand urc ->
        if (!params.register) return

        // Copied from the 'save' action of the register controller
        // generated by the Spring Security plugin (with some substantial
        // modifications).
        def role = Role.findByAuthority("ROLE_USER")
        if (!role) {
            urc.password = null
            urc.passwordRepeat = null
            flash.message = 'Default Role not found.'
            return [ userDetails: urc ]
        }

        if (!urc.hasErrors()) {
            def props = urc.properties
            def user = new User(props)
            user.profile = new Profile(props)

            // Put the encoded password in the database.
            user.password = springSecurityService.encodePassword(urc.password)
            user.emailShow = true

            // We've validated the user - now it's time to save it.
            if (!user.save()) {
                urc.password = null
                urc.passwordRepeat = null
                flash.message = "Error registering user"
                return [ userDetails: user ]
            }

            UserRole.create user, role
            String emailContent = """\
                    You have signed up for an account at:

                    ${request.scheme}://${request.serverName}:${request.serverPort}${request.contextPath}

                    Here are the details of your account:
                    -------------------------------------
                    LoginName: ${user.userId}
                    Email: ${user.email}
                    Full Name: ${user.userRealName ?: '<unknown>'}
                    """.stripIndent()

            sendMail {
                to      user.profile.email
                subject "[${request.contextPath}] Account Signed Up"
                body    emailContent
            }

            flash.message = "Successfully registered user - you can now log in"
            redirect(uri: '/')
        }
        else {
            urc.password = null
            urc.passwordRepeat = null
            flash.message = "Error registering user"
            return [ userDetails: urc ]
        }
    }

    def register3 = {
        // exercise for the reader
    }

    def profile = {
        
        if (params.id) {
            def user = User.findByUserId(params.id)
            if (user) {
                return [ profile : user.profile, userId : user.userId ]
            } else {
                response.sendError(404)
            }
        }

    }

    def stats = {
        User user = User.findByUserId(params.userId)
        if (user) {
            def sdf = new java.text.SimpleDateFormat('E')
            def postsOnDay = [:]
            user.posts.each { post ->
                def dayOfWeek = sdf.format(post.dateCreated)
                if (postsOnDay[dayOfWeek]) {
                    postsOnDay[dayOfWeek]++
                } else {
                    postsOnDay[dayOfWeek] = 1
                }
            }
            return [ userId: params.userId, postsOnDay: postsOnDay ]
        } else {
            flash.message = "No stats available for that user"
            redirect(uri: "/")
        }

    }

    def feed = {


        User user = User.findByUserId(params.userId)
        def format = params.format ?: "atom"

        def feedUri = g.createLinkTo(dir: '/') + "${params.userId}/feed/${format}"

        if (user) {

            def pc = Post.createCriteria()
            def posts = pc.list {
                eq('user', user)
                maxResults(5)
                order("dateCreated", "desc")
            }

            def fb = new feedsplugin.FeedBuilder()

            fb.feed  {
                title = "Hubbub Feed for ${user.userId}"
                link = feedUri
                description = "All of the latest hubbub posts for ${user.userId}"
                posts.each { post ->
                    entry(post.content[0..10] + "...") {
                        publishedDate = post.dateCreated
                        link =  g.createLink(absolute: true, controller: 'user', id: user.userId) + "#" + post.id
                        content(type:'text/html') {
                            post.content
                        }
                    }
                }
            }
            def feedXml = fb.render( format )
            render(text: feedXml, contentType:"text/xml")
        }

    }

    def welcomeEmail =  {

        if (params.email) {
            sendMail {
                to params.email
                subject "Welcome to Hubbub!"
                body(view: "welcomeEmail", model: [ email: params.email ])
		    }
            flash.message = "Welcome aboard"
        }
        redirect(uri: "/")

    }



}


class UserRegistrationCommand {

    String userId
    String password
    String passwordRepeat

    byte[] photo
    String fullName
    String bio
    String homepage
    String email
    String timezone
    String country
    String jabberAddress

    static constraints = {
        userId(blank: false, size: 3..20, validator: { userId, urc, errors ->
            if (userId && User.findByUserId(userId)) {
                errors.rejectValue(
                        "userId",
                        "error.user.exists",
                        [ userId ] as Object[],
                        "User ${userId} already exists")
            }
        })

        // Ensure password does not match userid
        password(size: 6..30, blank: false,
                 validator: { passwd, urc ->
                    return passwd != urc.userId
                })
        passwordRepeat(nullable: false,
                validator: { passwd2, urc ->
                    return passwd2 == urc.password
                })
        fullName(nullable: true)
        bio(nullable: true, maxSize: 1000)
        homepage(url: true, nullable: true)
        email(email: true, nullable: true)
        photo(nullable: true)
        country(nullable: true)
        timezone(nullable: true)
        jabberAddress(email: true, nullable: true)
    }

}
